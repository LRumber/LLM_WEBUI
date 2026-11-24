<script setup lang="ts">
import { computed } from 'vue'
import { compactUsage, usageScale, usageTicks, type UsageChartRow } from './usageChart'

const props = defineProps<{ title: string; rows: UsageChartRow[]; unit: string; loading?: boolean }>()
const width = computed(() => Math.max(550, 100 + props.rows.length * 82))
const ticks = computed(() => usageTicks(Math.max(0, ...props.rows.flatMap(row => [row.api, row.chat]))))
const y = (value: number) => 280 - usageScale(value) / usageScale(ticks.value[ticks.value.length - 1]!) * 230
const step = computed(() => (width.value - 100) / Math.max(1, props.rows.length))
const x = (index: number) => 88 + step.value * (index + 0.5)
const series = [{ key: 'api', label: 'API 调用', color: '#245db8' }, { key: 'chat', label: '网页对话', color: '#008773' }] as const
</script>

<template>
  <article class="usage-bar-card" :aria-busy="loading">
    <h3>{{ title }}</h3>
    <p v-if="loading" class="chart-empty">加载中…</p>
    <p v-else-if="!rows.length || !rows.some(row => row.api || row.chat)" class="chart-empty">暂无用量数据</p>
    <div v-else class="chart-scroll" tabindex="0" :aria-label="`${title}，可横向滚动查看全部分类`">
      <svg :viewBox="`0 0 ${width} 365`" :style="{ minWidth: `${Math.max(480, 88 + rows.length * 65)}px` }" role="img" :aria-label="title">
        <title>{{ title }}：API 调用与网页对话对比</title>
        <g v-for="tick in ticks" :key="tick">
          <line x1="88" :x2="width - 12" :y1="y(tick)" :y2="y(tick)" stroke="#dde5ef" />
          <text x="76" :y="y(tick) + 4" text-anchor="end" class="axis-text">{{ compactUsage(tick) }}</text>
        </g>
        <g v-for="(row, index) in rows" :key="row.key">
          <g v-for="(item, seriesIndex) in series" :key="item.key">
            <rect :x="x(index) + (seriesIndex === 0 ? -34 : 2)" :y="y(row[item.key])" width="32" :height="280 - y(row[item.key])" :fill="item.color">
              <title>{{ row.label }} · {{ item.label }}：{{ row[item.key].toLocaleString('zh-CN') }} {{ unit }}</title>
            </rect>
            <text :x="x(index) + (seriesIndex === 0 ? -18 : 18)" :y="y(row[item.key]) - (seriesIndex === 1 && Math.abs(y(row.api) - y(row.chat)) < 18 ? 22 : 8)" text-anchor="middle" :fill="item.color" class="value-text">{{ compactUsage(row[item.key]) }}</text>
          </g>
          <foreignObject :x="x(index) - step / 2 + 4" y="292" :width="step - 8" height="65">
            <div class="category-label" :title="row.label">{{ row.label }}</div>
          </foreignObject>
        </g>
      </svg>
    </div>
  </article>
</template>

<style scoped>
.usage-bar-card { min-width: 0; padding: 18px 12px 8px; background: #fff; border: 1px solid #e4e9ef; border-radius: 10px; }
h3 { margin: 0 8px 10px; color: #172b49; font-size: 16px; }
.chart-scroll { overflow-x: auto; }
svg { display: block; width: 100%; font-family: inherit; }
.axis-text { fill: #536783; font-size: 12px; }
.value-text { font-size: 11px; }
.category-label { color: #536783; font-size: 12px; text-align: center; overflow-wrap: anywhere; line-height: 17px; }
.chart-empty { display: grid; place-items: center; min-height: 260px; color: #7b8380; font-size: 13px; }
</style>
