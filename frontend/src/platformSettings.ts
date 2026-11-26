export interface PlatformSettings {
  platformName: string
  loginDescription: string
  adminContact: string
  maxUploadBytes: number
  allowedExtensions: string
}
export const defaultPlatformSettings: PlatformSettings = {
  platformName: '智能工作台', loginDescription: '使用组织账号进入对话、知识库与智能工坊。', adminContact: '',
  maxUploadBytes: 104857600,
  allowedExtensions: '.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.md,.markdown,.png,.jpg,.jpeg,.webp,.gif,.bmp,.tif,.tiff,.wav,.mp3,.m4a,.webm,.ogg,.flac,.mp4,.aac',
}
export async function loadPlatformSettings(): Promise<PlatformSettings> {
  const response = await fetch('/api/platform/settings')
  if (!response.ok) throw new Error('平台设置加载失败')
  return response.json()
}
export function uploadPolicyError(file: { name: string; size: number }, settings: PlatformSettings): string {
  if (file.size <= 0) return '不能上传空文件'
  if (file.size > settings.maxUploadBytes) return `文件超过上传上限（${Number((settings.maxUploadBytes / 1048576).toFixed(2))} MB）`
  if (!settings.allowedExtensions.split(',').some(ext => file.name.toLowerCase().endsWith(ext.trim().toLowerCase()))) return '该文件类型未获平台允许'
  return ''
}
