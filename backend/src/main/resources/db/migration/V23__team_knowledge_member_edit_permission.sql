-- Team members should be able to manage documents inside team knowledge bases.
-- Earlier versions synchronized normal team members as viewers, which blocked
-- document upload and deletion even though the team space itself was accessible.
UPDATE knowledge_member km
   SET member_role = 'editor'
  FROM knowledge_base kb
  JOIN team_member tm ON tm.team_id = kb.scope_ref
 WHERE km.knowledge_base_id = kb.id
   AND km.user_id = tm.user_id
   AND kb.space_type = 'team'
   AND kb.status = 'active'
   AND km.permission_source = 'team'
   AND tm.member_role = 'member'
   AND km.member_role = 'viewer';
