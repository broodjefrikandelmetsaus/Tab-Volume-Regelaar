create index if not exists volume_remote_controllers_device_idx on public.volume_remote_controllers (device_id);
create policy "deny direct API access to volume_remote_devices" on public.volume_remote_devices for all to anon, authenticated using (false) with check (false);
create policy "deny direct API access to volume_remote_controllers" on public.volume_remote_controllers for all to anon, authenticated using (false) with check (false);
create policy "deny direct API access to volume_remote_commands" on public.volume_remote_commands for all to anon, authenticated using (false) with check (false);
create policy "deny direct API access to volume_remote_pair_attempts" on public.volume_remote_pair_attempts for all to anon, authenticated using (false) with check (false);
