<script setup>
import { computed } from 'vue'

const props = defineProps({
  name: { type: String, required: true },
  size: { type: [Number, String], default: 18 },
})

const paths = {
  channels: ['M4 7h16', 'M4 12h10', 'M4 17h7', 'M17 12l3 3-3 3'],
  aliases: ['M8 7h12', 'M8 12h8', 'M8 17h5', 'M4 7h.01', 'M4 12h.01', 'M4 17h.01'],
  models: ['M4 6h16v12H4z', 'M8 10h8', 'M8 14h5'],
  list: ['M8 6h12', 'M8 12h12', 'M8 18h12', 'M4 6h.01', 'M4 12h.01', 'M4 18h.01'],
  keys: ['M14 7a4 4 0 1 0-3.5 3.97L5 16.5V19h2.5l1-1v-1.5H10l1-1v-1.5h1.5L14 12.5'],
  logs: ['M5 5h14v14H5z', 'M8 9h8', 'M8 13h8', 'M8 17h5'],
  chart: ['M4 20V10', 'M10 20V4', 'M16 20V13', 'M22 20H2'],
  logout: ['M10 5H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h4', 'M14 8l4 4-4 4', 'M9 12h9'],
  menu: ['M4 7h16', 'M4 12h16', 'M4 17h16'],
  close: ['M6 6l12 12', 'M18 6L6 18'],
  plus: ['M12 5v14', 'M5 12h14'],
  edit: ['M4 20h4l10-10-4-4L4 16v4z', 'M13 7l4 4'],
  trash: ['M5 7h14', 'M9 7V5h6v2', 'M8 7l1 13h6l1-13', 'M10 11v5', 'M14 11v5'],
  copy: ['M9 9h10v10H9z', 'M5 15V5h10'],
  check: ['M5 12l4 4L19 6'],
  search: ['M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z', 'M16 16l4 4'],
  refresh: ['M20 12a8 8 0 1 1-2.34-5.66', 'M20 5v5h-5'],
  camera: ['M4 8h3l1.5-2h7L17 8h3v11H4z', 'M12 16a3 3 0 1 0 0-6 3 3 0 0 0 0 6z'],
  lock: ['M6 10h12v10H6z', 'M9 10V7a3 3 0 0 1 6 0v3'],
  users: ['M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2', 'M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8', 'M22 21v-2a4 4 0 0 0-3-3.87', 'M16 3.13a4 4 0 0 1 0 7.75'],
  chevron: ['M7 9l5 5 5-5'],
  up: ['M7 14l5-5 5 5'],
  down: ['M7 10l5 5 5-5'],
  arrow: ['M5 12h14', 'M13 6l6 6-6 6'],
  shield: ['M12 3l7 3v5c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6l7-3z'],
  bolt: ['M13 3L5 14h6l-1 7 8-11h-6l1-7z'],
  price: [
    'M12 3v18',
    'M16.5 7.5c0-1.7-2-3-4.5-3s-4.5 1.3-4.5 3 2 3 4.5 3 4.5 1.3 4.5 3-2 3-4.5 3-4.5-1.3-4.5-3',
  ],
  sun: [
    'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z',
    'M12 2v2',
    'M12 20v2',
    'M4.93 4.93l1.41 1.41',
    'M17.66 17.66l1.41 1.41',
    'M2 12h2',
    'M20 12h2',
    'M6.34 17.66l-1.41 1.41',
    'M19.07 4.93l-1.41 1.41',
  ],
  moon: [
    'M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z',
  ],
  eye: [
    'M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z',
    'M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z',
  ],
  'eye-off': [
    'M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24',
    'M1 1l22 22',
  ],
  coffee: [
    'M18 8h1a4 4 0 0 1 0 8h-1',
    'M2 8h16v9a4 4 0 0 1-4 4H6a4 4 0 0 1-4-4V8z',
    'M6 1v3',
    'M10 1v3',
    'M14 1v3',
  ],
}

const iconPaths = computed(() => paths[props.name] || paths.channels)
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.8"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path v-for="path in iconPaths" :key="path" :d="path" />
  </svg>
</template>
