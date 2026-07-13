-- Normal team members can read team knowledge bases, but only team owners/admins
-- should manage knowledge documents. This migration reverses any member entries
-- that were temporarily elevated to editor.
UPDATE knowledge_member km
   SET member_role = 'viewer'
  FROM knowledge_base kb
  JOIN team_member tm ON tm.team_id = kb.scope_ref
 WHERE km.knowledge_base_id = kb.id
   AND km.user_id = tm.user_id
   AND kb.space_type = 'team'
   AND kb.status = 'active'
   AND km.permission_source = 'team'
   AND tm.member_role = 'member'
   AND km.member_role = 'editor';
