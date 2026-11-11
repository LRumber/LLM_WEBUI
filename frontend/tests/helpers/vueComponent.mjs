import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { parse, compileScript } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as vue from 'vue'

const require = createRequire(import.meta.url)
const cache = new Map()
const rowsKey = Symbol('table rows')
const widgetCache = new Map()

// Keep Vue's real reactivity, event handlers and template branches; replace only UI widgets.
const widgets = new Proxy({}, {
  get(_, name) {
    if (name === 'ElLoading') return { directive: {} }
    if (widgetCache.has(name)) return widgetCache.get(name)
    const widget = vue.defineComponent({
      inheritAttrs: false,
      setup(_, { attrs, slots }) {
        const rows = vue.inject(rowsKey, null)
        if (name === 'ElTable') vue.provide(rowsKey, () => attrs.data ?? [])
        return () => vue.h(name, { ...attrs },
          name === 'ElDialog' && !attrs['model-value'] ? [] :
            name === 'ElTableColumn' && rows
              ? rows().flatMap(row => slots.default?.({ row }) ?? [])
              : [...(slots.default?.() ?? []), ...(slots.label?.() ?? [])])
      },
    })
    widgetCache.set(name, widget)
    return widget
  },
})

export function component(path) {
  const url = new URL(path, import.meta.url)
  if (cache.has(url.href)) return cache.get(url.href)
  const { descriptor } = parse(readFileSync(url, 'utf8'), { filename: url.pathname })
  const script = compileScript(descriptor, { id: url.href, inlineTemplate: true })
  const code = ts.transpileModule(script.content, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText
  const module = { exports: {} }
  new Function('require', 'module', 'exports', code)(id => {
    if (id === 'vue') return vue
    if (id === 'element-plus' || id === 'lucide-vue-next') return widgets
    if (id.endsWith('.css')) return {}
    if (id.endsWith('.vue')) return { default: component(new URL(id, url).href) }
    return require(id)
  }, module, module.exports)
  cache.set(url.href, module.exports.default)
  return module.exports.default
}

const node = (type, text = '') => ({ type, text, props: {}, children: [], parent: null })
const renderer = vue.createRenderer({
  createElement: node, createText: text => node('#text', text), createComment: () => node('#comment'),
  setText: (el, text) => { el.text = text },
  setElementText: (el, text) => { el.text = text; el.children = [] },
  patchProp: (el, key, _, value) => { el.props[key] = value },
  insert(el, parent, anchor = null) {
    if (el.parent) this.remove(el)
    const index = anchor ? parent.children.indexOf(anchor) : -1
    parent.children.splice(index < 0 ? parent.children.length : index, 0, el)
    el.parent = parent
  },
  remove(el) {
    if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1)
    el.parent = null
  },
  parentNode: el => el.parent,
  nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null,
})

export async function settle() {
  for (let i = 0; i < 12; i++) { await Promise.resolve(); await vue.nextTick() }
}
export async function mount(path, props) {
  const root = node('root')
  const app = renderer.createApp(component(path), props)
  app.mount(root)
  await settle()
  return { root, unmount: () => app.unmount() }
}
export function findAll(root, type) {
  return [ ...(root.type === type ? [root] : []), ...root.children.flatMap(child => findAll(child, type)) ]
}
export const text = root => root.text + root.children.map(text).join('')
export async function update(el, value) { el.props['onUpdate:modelValue'](value); await settle() }
export async function click(el) { el.props.onClick(); await settle() }
export async function submit(el) { el.props.onSubmit({ preventDefault() {} }); await settle() }
