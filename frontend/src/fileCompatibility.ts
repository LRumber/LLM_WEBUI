export type FileKind = 'image' | 'document' | 'audio' | 'unsupported'

export interface FileCompatibility {
  kind: FileKind
  supported: boolean
  reason?: string
}

const documentExtensions = new Set(['pdf', 'docx', 'xlsx', 'pptx', 'txt', 'md', 'markdown'])

/** Matches a browser file to model capabilities and platform extraction tools. */
export const assessFileCompatibility = (
  file: { name: string; type: string },
  capabilities: string[],
): FileCompatibility => {
  const extension = file.name.split('.').pop()?.toLowerCase() ?? ''
  if (file.type.startsWith('image/')) {
    return capabilities.includes('vision')
      ? { kind: 'image', supported: true }
      : { kind: 'image', supported: false, reason: '当前大语言模型不支持图片识别，请切换到多模态模型。' }
  }
  if (file.type.startsWith('audio/')) {
    return capabilities.includes('speech_to_text')
      ? { kind: 'audio', supported: true }
      : { kind: 'audio', supported: false, reason: '当前模型未配置语音识别能力。' }
  }
  if (documentExtensions.has(extension) || file.type.startsWith('text/')) {
    return { kind: 'document', supported: true }
  }
  return { kind: 'unsupported', supported: false, reason: '该文件类型尚不支持解析。' }
}
