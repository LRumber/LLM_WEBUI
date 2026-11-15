export type AdminPage = 'overview' | 'users' | 'usage' | 'models' | 'api' | 'prompts' | 'announcements' | 'settings' | 'feedback'

const pages = new Set<AdminPage>(['users', 'usage', 'models', 'api', 'prompts', 'announcements', 'settings', 'feedback'])

/** Resolves stable administration URLs without requiring hash-based single-page sections. */
export const resolveAdminPage = (pathname: string): AdminPage => {
  const segment = pathname.split('/').filter(Boolean)[1] as AdminPage | undefined
  return segment && pages.has(segment) ? segment : 'overview'
}

/** Supplies route-specific page headings for the shared authenticated administration shell. */
export const adminPageMeta: Record<AdminPage, [string, string]> = {
  feedback: ['意见反馈', '查看用户提交的问题与建议'],
  overview: ['平台概览', '用户、对话、API 与资源运行摘要'],
  users: ['用户管理', 'OAuth2 影子用户、权限与 API 额度'],
  usage: ['用量统计', '网页对话与 API 调用的 Token 使用趋势'],
  models: ['模型管理', '模型接入、前台可见性与健康状态'],
  api: ['API 管理', '统一 API 模型开放与 Key 管理'],
  prompts: ['系统提示词', '维护全平台可用的系统提示词模板'],
  announcements: ['公告管理', '发布新模型、新功能与维护通知'],
  settings: ['系统设置', '运行策略与安全边界'],
}
