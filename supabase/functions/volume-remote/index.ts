import { createClient } from "npm:@supabase/supabase-js@2.99.0";
const corsHeaders = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type", "Access-Control-Allow-Methods": "POST, OPTIONS" };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { ...corsHeaders, "Content-Type": "application/json" } });
const hex = (bytes: Uint8Array) => Array.from(bytes).map((b) => b.toString(16).padStart(2, "0")).join("");
async function sha256(value: string): Promise<string> { return hex(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)))); }
function randomToken(bytes = 32): string { return btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(bytes)))).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", ""); }
function validId(v: unknown): v is string { return typeof v === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(v); }
function validSecret(v: unknown): v is string { return typeof v === "string" && v.length >= 32 && v.length <= 256; }
const keys = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") ?? "{}");
const serviceKey = keys.default ?? Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
const url = Deno.env.get("SUPABASE_URL");
if (!serviceKey || !url) throw new Error("Server configuration missing");
const db = createClient(url, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json({ error: "METHOD_NOT_ALLOWED" }, 405);
  try {
    const body = await req.json();
    const action = body?.action;
    const ip = req.headers.get("cf-connecting-ip") ?? req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ?? "unknown";
    const ipHash = await sha256(ip + ":" + url);

    if (action === "register") {
      const { deviceId, deviceSecret, pairingCode, status } = body;
      if (!validId(deviceId) || !validSecret(deviceSecret) || typeof pairingCode !== "string" || !/^\d{6}$/.test(pairingCode)) return json({ error: "INVALID_REGISTRATION" }, 400);
      const secretHash = await sha256(deviceSecret);
      const { data: existing, error: lookupError } = await db.from("volume_remote_devices").select("device_secret_hash").eq("device_id", deviceId).maybeSingle();
      if (lookupError) throw lookupError;
      if (existing && existing.device_secret_hash !== secretHash) return json({ error: "DEVICE_AUTH_FAILED" }, 401);
      const expiry = new Date(Date.now() + 10 * 60 * 1000).toISOString();
      const { error } = await db.from("volume_remote_devices").upsert({
        device_id: deviceId, device_secret_hash: secretHash, pairing_code_hash: await sha256(pairingCode),
        pairing_expires_at: expiry, last_seen_at: new Date().toISOString(), status: status && typeof status === "object" ? status : {},
      }, { onConflict: "device_id" });
      if (error) throw error;
      return json({ ok: true, pairingExpiresAt: expiry });
    }

    if (action === "pair") {
      const code = body?.pairingCode;
      if (typeof code !== "string" || !/^\d{6}$/.test(code)) return json({ error: "INVALID_CODE" }, 400);
      const { error: attemptError } = await db.from("volume_remote_pair_attempts").insert({ ip_hash: ipHash });
      if (attemptError) throw attemptError;
      const { count, error: countError } = await db.from("volume_remote_pair_attempts").select("id", { count: "exact", head: true })
        .eq("ip_hash", ipHash).gte("attempted_at", new Date(Date.now() - 5 * 60 * 1000).toISOString());
      if (countError) throw countError;
      if ((count ?? 0) > 8) return json({ error: "RATE_LIMITED" }, 429);
      const { data: device, error } = await db.from("volume_remote_devices").select("device_id")
        .eq("pairing_code_hash", await sha256(code)).gt("pairing_expires_at", new Date().toISOString()).maybeSingle();
      if (error) throw error;
      if (!device) return json({ error: "PAIRING_CODE_INVALID_OR_EXPIRED" }, 401);
      const controllerSecret = randomToken();
      const { error: insertError } = await db.from("volume_remote_controllers").insert({ device_id: device.device_id, controller_secret_hash: await sha256(controllerSecret) });
      if (insertError) throw insertError;
      await db.from("volume_remote_devices").update({ pairing_code_hash: null, pairing_expires_at: null, paired_at: new Date().toISOString() }).eq("device_id", device.device_id);
      return json({ ok: true, deviceId: device.device_id, controllerSecret });
    }

    if (["poll", "ack", "status", "send"].includes(action)) {
      const { deviceId, secret } = body;
      if (!validId(deviceId) || !validSecret(secret)) return json({ error: "INVALID_CREDENTIALS" }, 400);
      const secretHash = await sha256(secret);
      if (action === "poll" || action === "ack") {
        const { data: device, error } = await db.from("volume_remote_devices").select("device_secret_hash").eq("device_id", deviceId).maybeSingle();
        if (error) throw error;
        if (!device || device.device_secret_hash !== secretHash) return json({ error: "DEVICE_AUTH_FAILED" }, 401);
        if (action === "ack") {
          const id = Number(body.commandId);
          if (!Number.isSafeInteger(id) || id <= 0) return json({ error: "INVALID_COMMAND_ID" }, 400);
          const { error: ackError } = await db.from("volume_remote_commands").update({ delivered_at: new Date().toISOString() }).eq("id", id).eq("device_id", deviceId).is("delivered_at", null);
          if (ackError) throw ackError;
          return json({ ok: true });
        }
        const update: Record<string, unknown> = { last_seen_at: new Date().toISOString() };
        if (body.status && typeof body.status === "object") update.status = body.status;
        const { error: updateError } = await db.from("volume_remote_devices").update(update).eq("device_id", deviceId);
        if (updateError) throw updateError;
        const { data: commands, error: commandError } = await db.from("volume_remote_commands").select("id, action, percent")
          .eq("device_id", deviceId).is("delivered_at", null).order("id", { ascending: true }).limit(5);
        if (commandError) throw commandError;
        return json({ ok: true, commands: commands ?? [] });
      }

      const { data: controller, error: controllerError } = await db.from("volume_remote_controllers").select("controller_id")
        .eq("device_id", deviceId).eq("controller_secret_hash", secretHash).eq("is_active", true).maybeSingle();
      if (controllerError) throw controllerError;
      if (!controller) return json({ error: "CONTROLLER_AUTH_FAILED" }, 401);
      await db.from("volume_remote_controllers").update({ last_seen_at: new Date().toISOString() }).eq("controller_id", controller.controller_id);
      if (action === "status") {
        const { data: device, error } = await db.from("volume_remote_devices").select("status, last_seen_at").eq("device_id", deviceId).single();
        if (error) throw error;
        return json({ ok: true, status: device.status ?? {}, lastSeenAt: device.last_seen_at });
      }

      const command = body?.command;
      const percent = body?.percent;
      if (!["set", "setBrightness", "lock"].includes(command) || (command !== "lock" && (!Number.isInteger(percent) || percent < 0 || percent > 100))) return json({ error: "INVALID_COMMAND" }, 400);
      const { data: recent, error: recentError } = await db.from("volume_remote_commands").select("id").eq("device_id", deviceId)
        .gte("created_at", new Date(Date.now() - 100).toISOString()).limit(1);
      if (recentError) throw recentError;
      if (recent?.length) return json({ error: "COMMAND_THROTTLED" }, 429);

      if (command === "lock") {
        const { error: insertError } = await db.from("volume_remote_commands").insert({ device_id: deviceId, action: command, percent: null });
        if (insertError) throw insertError;
      } else {
        const { data: pending, error: pendingError } = await db.from("volume_remote_commands").select("id")
          .eq("device_id", deviceId).eq("action", command).is("delivered_at", null).order("id", { ascending: false }).limit(1).maybeSingle();
        if (pendingError) throw pendingError;
        if (pending) {
          const { error: updateError } = await db.from("volume_remote_commands").update({ percent, created_at: new Date().toISOString() }).eq("id", pending.id);
          if (updateError) throw updateError;
        } else {
          const { error: insertError } = await db.from("volume_remote_commands").insert({ device_id: deviceId, action: command, percent });
          if (insertError) throw insertError;
        }
      }
      return json({ ok: true });
    }
    return json({ error: "UNKNOWN_ACTION" }, 400);
  } catch (error) {
    console.error("volume-remote error", error);
    return json({ error: "INTERNAL_ERROR" }, 500);
  }
});
