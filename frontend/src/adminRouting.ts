export type AdminPage = 'overview' | 'users' | 'usage' | 'models' | 'prompts' | 'settings'

const pages = new Set<AdminPage>(['users', 'usage', 'models', 'prompts', 'settings'])

/** Resolves stable administration URLs without requiring hash-based single-page sections. */
export const resolveAdminPage = (pathname: string): AdminPage => {
  const segment = pathname.split('/').filter(Boolean)[1] as AdminPage | undefined
  return segment && pages.has(segment) ? segment : 'overview'
}

/** Supplies route-specific page headings for the shared authenticated administration shell. */
export const adminPageMeta: Record<AdminPage, [string, string]> = {
  overview: ['平台概览', '用户、对话与资源运行摘要'],
  users: ['用户管理', 'OAuth2 影子用户、状态与后台权限'],
  usage: ['用量统计', '请求、会话与 Token 使用趋势'],
  models: ['模型管理', '模型接入、前台可见性与健康状态'],
  prompts: ['系统提示词', '维护全平台可用的系统提示词模板'],
  settings: ['系统设置', '运行策略与安全边界'],
}
