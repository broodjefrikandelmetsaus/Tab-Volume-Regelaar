-- Allow the remote screen-lock commands sent by the Controller.
-- Keep the existing supported actions and add the two screen-lock actions.
alter table public.volume_remote_commands
  drop constraint if exists volume_remote_commands_action_check;

alter table public.volume_remote_commands
  add constraint volume_remote_commands_action_check
  check (
    action = any (
      array[
        'set'::text,
        'setBrightness'::text,
        'lock'::text,
        'wifi'::text,
        'wake'::text,
        'openKids'::text,
        'screenLock'::text,
        'screenUnlock'::text
      ]
    )
  );
