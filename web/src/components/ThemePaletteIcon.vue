<script setup lang="ts">
import { useId } from 'vue'
import type { ThemePaletteId } from '../utils/theme'

withDefaults(defineProps<{
  palette: ThemePaletteId
  size?: number | string
}>(), {
  size: 15,
})

const uid = useId()
const grad = (name: string) => `${uid}-${name}`
const refUrl = (name: string) => `url(#${grad(name)})`
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 16 16"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
    aria-hidden="true"
  >
    <template v-if="palette === 'gemini'">
      <defs>
        <linearGradient :id="grad('gemini')" x1="1" y1="1" x2="15" y2="15" gradientUnits="userSpaceOnUse">
          <stop offset="0%" stop-color="#4285F4" />
          <stop offset="35%" stop-color="#8B5CF6" />
          <stop offset="70%" stop-color="#EC4899" />
          <stop offset="100%" stop-color="#F59E0B" />
        </linearGradient>
      </defs>
      <path d="M8 1C8 4.866 4.866 8 1 8C4.866 8 8 11.134 8 15C8 11.134 11.134 8 15 8C11.134 8 8 4.866 8 1Z" :fill="refUrl('gemini')" />
      <circle cx="8" cy="8" r="1.6" fill="#FFFFFF" fill-opacity="0.95" />
    </template>

    <template v-else-if="palette === 'sky'">
      <defs>
        <linearGradient :id="grad('sun')" x1="4" y1="4" x2="12" y2="12" gradientUnits="userSpaceOnUse">
          <stop offset="0%" stop-color="#FDE047" />
          <stop offset="100%" stop-color="#EA580C" />
        </linearGradient>
        <linearGradient :id="grad('ray')" x1="8" y1="1" x2="8" y2="15" gradientUnits="userSpaceOnUse">
          <stop offset="0%" stop-color="#F59E0B" />
          <stop offset="100%" stop-color="#D97706" />
        </linearGradient>
      </defs>
      <circle cx="8" cy="8" r="3.4" :fill="refUrl('sun')" />
      <path d="M8 1.5V3M8 13V14.5M1.5 8H3M13 8H14.5M3.4 3.4L4.5 4.5M11.5 11.5L12.6 12.6M3.4 12.6L4.5 11.5M11.5 4.5L12.6 3.4" :stroke="refUrl('ray')" stroke-width="1.6" stroke-linecap="round" />
    </template>

    <template v-else-if="palette === 'cyan'">
      <defs>
        <linearGradient :id="grad('leaf')" x1="1" y1="2" x2="15" y2="14" gradientUnits="userSpaceOnUse">
          <stop offset="0%" stop-color="#34D399" />
          <stop offset="60%" stop-color="#059669" />
          <stop offset="100%" stop-color="#0D9488" />
        </linearGradient>
        <linearGradient :id="grad('wave')" x1="2" y1="13" x2="14" y2="13" gradientUnits="userSpaceOnUse">
          <stop offset="0%" stop-color="#06B6D4" />
          <stop offset="100%" stop-color="#10B981" />
        </linearGradient>
      </defs>
      <path d="M14 2C10.5 2 6 4 4 8C2.5 11 3.5 13 4.5 13C6.5 13 11 11.5 13 8C14 6 14 3.5 14 2Z" :fill="refUrl('leaf')" />
      <path d="M4 8C7 7.5 10 5.5 12 3" stroke="#A7F3D0" stroke-width="1.2" stroke-linecap="round" />
      <path d="M2 13.5C4 12 6.5 12 8.5 13.5C10.5 15 13 15 15 13.5" :stroke="refUrl('wave')" stroke-width="1.5" stroke-linecap="round" />
    </template>

    <template v-else-if="palette === 'orange'">
      <defs>
        <linearGradient :id="grad('sunset')" x1="8" y1="3" x2="8" y2="9.5" gradientUnits="userSpaceOnUse">
          <stop offset="0%" stop-color="#FBBF24" />
          <stop offset="60%" stop-color="#F97316" />
          <stop offset="100%" stop-color="#E11D48" />
        </linearGradient>
        <linearGradient :id="grad('sea')" x1="2" y1="10" x2="14" y2="14.5" gradientUnits="userSpaceOnUse">
          <stop offset="0%" stop-color="#EA580C" />
          <stop offset="50%" stop-color="#FB923C" />
          <stop offset="100%" stop-color="#FBBF24" />
        </linearGradient>
      </defs>
      <path d="M3.5 9.5C3.5 7 5.5 5 8 5C10.5 5 12.5 7 12.5 9.5H3.5Z" :fill="refUrl('sunset')" />
      <path d="M8 2.5V3.8M4.2 4.2L5.1 5.1M11.8 4.2L10.9 5.1" stroke="#FBBF24" stroke-width="1.3" stroke-linecap="round" />
      <path d="M2 10.5H14M3.5 12.5H12.5M5.5 14.5H10.5" :stroke="refUrl('sea')" stroke-width="1.4" stroke-linecap="round" />
    </template>

    <template v-else-if="palette === 'red'">
      <defs>
        <linearGradient :id="grad('petal')" x1="2" y1="2" x2="14" y2="14" gradientUnits="userSpaceOnUse">
          <stop offset="0%" stop-color="#FB7185" />
          <stop offset="50%" stop-color="#E11D48" />
          <stop offset="100%" stop-color="#BE123C" />
        </linearGradient>
        <radialGradient :id="grad('center')" cx="8" cy="8" r="2.2" gradientUnits="userSpaceOnUse">
          <stop offset="0%" stop-color="#FDE047" />
          <stop offset="100%" stop-color="#F59E0B" />
        </radialGradient>
      </defs>
      <circle cx="8" cy="4.8" r="2.7" :fill="refUrl('petal')" />
      <circle cx="11.2" cy="7.1" r="2.7" :fill="refUrl('petal')" />
      <circle cx="10" cy="11" r="2.7" :fill="refUrl('petal')" />
      <circle cx="6" cy="11" r="2.7" :fill="refUrl('petal')" />
      <circle cx="4.8" cy="7.1" r="2.7" :fill="refUrl('petal')" />
      <circle cx="8" cy="8" r="2.2" :fill="refUrl('center')" />
      <circle cx="8" cy="8" r="0.8" fill="#FFFBEB" />
    </template>

    <template v-else>
      <defs>
        <linearGradient :id="grad('orchid')" x1="3" y1="2" x2="13" y2="14" gradientUnits="userSpaceOnUse">
          <stop offset="0%" stop-color="#C4B5FD" />
          <stop offset="46%" stop-color="#8B5CF6" />
          <stop offset="100%" stop-color="#5B21B6" />
        </linearGradient>
        <linearGradient :id="grad('crystal')" x1="11" y1="1.9" x2="15" y2="5.8" gradientUnits="userSpaceOnUse">
          <stop offset="0%" stop-color="#FFFFFF" />
          <stop offset="100%" stop-color="#C084FC" />
        </linearGradient>
      </defs>
      <g>
        <ellipse cx="8" cy="5.15" rx="2.05" ry="3.1" transform="rotate(0 8 8.6)" :fill="refUrl('orchid')" />
        <ellipse cx="8" cy="5.15" rx="2.05" ry="3.1" transform="rotate(72 8 8.6)" :fill="refUrl('orchid')" />
        <ellipse cx="8" cy="5.15" rx="2.05" ry="3.1" transform="rotate(144 8 8.6)" :fill="refUrl('orchid')" />
        <ellipse cx="8" cy="5.15" rx="2.05" ry="3.1" transform="rotate(216 8 8.6)" :fill="refUrl('orchid')" />
        <ellipse cx="8" cy="5.15" rx="2.05" ry="3.1" transform="rotate(288 8 8.6)" :fill="refUrl('orchid')" />
        <circle cx="8" cy="8.6" r="1.5" fill="#F5E9FF" fill-opacity="0.96" />
        <circle cx="8" cy="8.6" r="0.55" fill="#6D28D9" />
        <path d="M8 3.15V6.4" stroke="#F3E8FF" stroke-width="0.8" stroke-linecap="round" stroke-opacity="0.8" />
      </g>
      <path d="M13.05 1.9L13.55 3.35L15 3.85L13.55 4.35L13.05 5.8L12.55 4.35L11.1 3.85L12.55 3.35Z" :fill="refUrl('crystal')" />
    </template>
  </svg>
</template>
