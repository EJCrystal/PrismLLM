<script setup lang="ts">
import type { ProtocolId } from '../types/api'

withDefaults(
  defineProps<{
    protocol: ProtocolId | string
    size?: number | string
  }>(),
  {
    size: 24,
  },
)
</script>

<template>
  <svg
    v-if="protocol === 'chat_completions'"
    :width="size"
    :height="size"
    viewBox="0 0 64 64"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
    aria-hidden="true"
    class="protocol-svg protocol-svg-chat"
  >
    <defs>
      <linearGradient id="proto-chat-bg" x1="6" y1="6" x2="58" y2="58" gradientUnits="userSpaceOnUse">
        <stop offset="0%" stop-color="#10B981" stop-opacity="0.22" />
        <stop offset="100%" stop-color="#06B6D4" stop-opacity="0.08" />
      </linearGradient>
      <linearGradient id="proto-chat-stroke" x1="6" y1="6" x2="58" y2="58" gradientUnits="userSpaceOnUse">
        <stop offset="0%" stop-color="#34D399" />
        <stop offset="100%" stop-color="#0891B2" />
      </linearGradient>
      <linearGradient id="proto-chat-main" x1="10" y1="12" x2="52" y2="50" gradientUnits="userSpaceOnUse">
        <stop offset="0%" stop-color="#34D399" />
        <stop offset="50%" stop-color="#10B981" />
        <stop offset="100%" stop-color="#0891B2" />
      </linearGradient>
      <linearGradient id="proto-chat-spark" x1="42" y1="8" x2="56" y2="22" gradientUnits="userSpaceOnUse">
        <stop offset="0%" stop-color="#A7F3D0" />
        <stop offset="100%" stop-color="#22D3EE" />
      </linearGradient>
    </defs>
    <rect x="4" y="4" width="56" height="56" rx="16" fill="url(#proto-chat-bg)" stroke="url(#proto-chat-stroke)" stroke-width="2" stroke-opacity="0.45" />
    <path d="M13 18C13 14.6863 15.6863 12 19 12H35C38.3137 12 41 14.6863 41 18V27C41 30.3137 38.3137 33 35 33H22L15.5 38V33H15C13.8954 33 13 32.1046 13 31V18Z" fill="#064E3B" fill-opacity="0.6" stroke="url(#proto-chat-stroke)" stroke-width="2" stroke-linejoin="round" />
    <path d="M19 20L23.5 23L19 26" stroke="#6EE7B7" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" />
    <line x1="26" y1="26" x2="33" y2="26" stroke="#6EE7B7" stroke-width="2.4" stroke-linecap="round" />
    <path d="M23 30C23 26.6863 25.6863 24 29 24H47C50.3137 24 53 26.6863 53 30V40C53 43.3137 50.3137 46 47 46H45V52L38 46H29C25.6863 46 23 43.3137 23 40V30Z" fill="url(#proto-chat-main)" stroke="#ECFDF5" stroke-width="1.6" stroke-opacity="0.6" stroke-linejoin="round" />
    <line x1="29" y1="32.5" x2="45" y2="32.5" stroke="#FFFFFF" stroke-width="2.5" stroke-linecap="round" />
    <line x1="29" y1="38.5" x2="39" y2="38.5" stroke="#E0F2FE" stroke-width="2.5" stroke-linecap="round" />
    <rect x="42.5" y="36" width="3.5" height="5" rx="1" fill="#FEF08A" />
    <path d="M49 9C49 13.5 50.5 15 55 15C50.5 15 49 16.5 49 21C49 16.5 47.5 15 43 15C47.5 15 49 13.5 49 9Z" fill="url(#proto-chat-spark)" />
  </svg>

  <svg
    v-else-if="protocol === 'responses'"
    :width="size"
    :height="size"
    viewBox="0 0 64 64"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
    aria-hidden="true"
    class="protocol-svg protocol-svg-responses"
  >
    <defs>
      <linearGradient id="proto-resp-bg" x1="6" y1="6" x2="58" y2="58" gradientUnits="userSpaceOnUse">
        <stop offset="0%" stop-color="#8B5CF6" stop-opacity="0.22" />
        <stop offset="100%" stop-color="#EC4899" stop-opacity="0.08" />
      </linearGradient>
      <linearGradient id="proto-resp-core" x1="12" y1="12" x2="52" y2="52" gradientUnits="userSpaceOnUse">
        <stop offset="0%" stop-color="#A855F7" />
        <stop offset="50%" stop-color="#7C3AED" />
        <stop offset="100%" stop-color="#EC4899" />
      </linearGradient>
      <linearGradient id="proto-resp-bolt" x1="26" y1="18" x2="39" y2="46" gradientUnits="userSpaceOnUse">
        <stop offset="0%" stop-color="#FDE047" />
        <stop offset="100%" stop-color="#F97316" />
      </linearGradient>
    </defs>
    <rect x="4" y="4" width="56" height="56" rx="16" fill="url(#proto-resp-bg)" stroke="url(#proto-resp-core)" stroke-width="2" stroke-opacity="0.45" />
    <path d="M15 32C15 22.6 22.6 15 32 15C39 15 45 19.2 47.6 25" stroke="url(#proto-resp-core)" stroke-width="2.4" stroke-linecap="round" />
    <path d="M49 32C49 41.4 41.4 49 32 49C25 49 19 44.8 16.4 39" stroke="url(#proto-resp-core)" stroke-width="2.4" stroke-linecap="round" />
    <path d="M44.5 25L48.2 26.2L49.5 22.4" stroke="#E879F9" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" />
    <path d="M19.5 39L15.8 37.8L14.5 41.6" stroke="#A855F7" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" />
    <circle cx="15" cy="32" r="3.5" fill="#1E1B4B" stroke="#C084FC" stroke-width="2" />
    <circle cx="49" cy="32" r="3.5" fill="#1E1B4B" stroke="#F472B6" stroke-width="2" />
    <polygon points="32,19 43,25.5 43,38.5 32,45 21,38.5 21,25.5" fill="url(#proto-resp-core)" stroke="#F5D0FE" stroke-width="1.8" stroke-opacity="0.8" />
    <path d="M34 22.5L26 33.5H32.5L30.5 42.5L38.5 31H32L34 22.5Z" fill="url(#proto-resp-bolt)" />
  </svg>

  <svg
    v-else-if="protocol === 'messages'"
    :width="size"
    :height="size"
    viewBox="0 0 64 64"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
    aria-hidden="true"
    class="protocol-svg protocol-svg-messages"
  >
    <defs>
      <linearGradient id="proto-msg-bg" x1="6" y1="6" x2="58" y2="58" gradientUnits="userSpaceOnUse">
        <stop offset="0%" stop-color="#D97757" stop-opacity="0.22" />
        <stop offset="100%" stop-color="#F59E0B" stop-opacity="0.08" />
      </linearGradient>
      <linearGradient id="proto-msg-terra" x1="10" y1="12" x2="54" y2="52" gradientUnits="userSpaceOnUse">
        <stop offset="0%" stop-color="#F97316" />
        <stop offset="55%" stop-color="#D97757" />
        <stop offset="100%" stop-color="#B45309" />
      </linearGradient>
      <linearGradient id="proto-msg-amber" x1="12" y1="12" x2="44" y2="26" gradientUnits="userSpaceOnUse">
        <stop offset="0%" stop-color="#FDE68A" />
        <stop offset="100%" stop-color="#F59E0B" />
      </linearGradient>
    </defs>
    <rect x="4" y="4" width="56" height="56" rx="16" fill="url(#proto-msg-bg)" stroke="url(#proto-msg-terra)" stroke-width="2" stroke-opacity="0.45" />
    <rect x="11" y="13" width="42" height="12" rx="5" fill="#431407" fill-opacity="0.65" stroke="url(#proto-msg-amber)" stroke-width="1.8" />
    <circle cx="17.5" cy="19" r="2.5" fill="#FBBF24" />
    <path d="M23 19H38" stroke="#FDE68A" stroke-width="2.2" stroke-linecap="round" stroke-opacity="0.9" />
    <path d="M42 16.5L45 19L42 21.5" stroke="#FBBF24" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
    <rect x="11" y="29" width="42" height="22" rx="6" fill="url(#proto-msg-terra)" stroke="#FFEDD5" stroke-width="1.5" stroke-opacity="0.65" />
    <line x1="17" y1="36" x2="33" y2="36" stroke="#FFF7ED" stroke-width="2.4" stroke-linecap="round" />
    <line x1="17" y1="43" x2="28" y2="43" stroke="#FED7AA" stroke-width="2.4" stroke-linecap="round" />
    <g transform="translate(42, 40)">
      <line x1="-5" y1="0" x2="5" y2="0" stroke="#FFF7ED" stroke-width="2.2" stroke-linecap="round" />
      <line x1="0" y1="-5" x2="0" y2="5" stroke="#FFF7ED" stroke-width="2.2" stroke-linecap="round" />
      <line x1="-3.5" y1="-3.5" x2="3.5" y2="3.5" stroke="#FFF7ED" stroke-width="1.8" stroke-linecap="round" />
      <line x1="-3.5" y1="3.5" x2="3.5" y2="-3.5" stroke="#FFF7ED" stroke-width="1.8" stroke-linecap="round" />
    </g>
  </svg>

  <svg
    v-else-if="protocol === 'gemini'"
    :width="size"
    :height="size"
    viewBox="0 0 64 64"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
    aria-hidden="true"
    class="protocol-svg protocol-svg-gemini"
  >
    <defs>
      <linearGradient id="proto-gem-bg" x1="6" y1="6" x2="58" y2="58" gradientUnits="userSpaceOnUse">
        <stop offset="0%" stop-color="#3B82F6" stop-opacity="0.22" />
        <stop offset="50%" stop-color="#8B5CF6" stop-opacity="0.16" />
        <stop offset="100%" stop-color="#EC4899" stop-opacity="0.1" />
      </linearGradient>
      <linearGradient id="proto-gem-ring" x1="8" y1="8" x2="56" y2="56" gradientUnits="userSpaceOnUse">
        <stop offset="0%" stop-color="#38BDF8" />
        <stop offset="50%" stop-color="#C084FC" />
        <stop offset="100%" stop-color="#FB7185" />
      </linearGradient>
      <linearGradient id="proto-gem-star" x1="10" y1="10" x2="54" y2="54" gradientUnits="userSpaceOnUse">
        <stop offset="0%" stop-color="#60A5FA" />
        <stop offset="45%" stop-color="#A855F7" />
        <stop offset="100%" stop-color="#F43F5E" />
      </linearGradient>
    </defs>
    <rect x="4" y="4" width="56" height="56" rx="16" fill="url(#proto-gem-bg)" stroke="url(#proto-gem-ring)" stroke-width="2" stroke-opacity="0.45" />
    <circle cx="32" cy="32" r="22" stroke="url(#proto-gem-ring)" stroke-width="1.5" stroke-opacity="0.4" stroke-dasharray="3 3" />
    <path d="M32 9C32 21.5 21.5 32 9 32C21.5 32 32 42.5 32 55C32 42.5 42.5 32 55 32C42.5 32 32 21.5 32 9Z" fill="url(#proto-gem-star)" stroke="#FFFFFF" stroke-width="1.4" stroke-opacity="0.7" />
    <path d="M32 21C32 27 27 32 21 32C27 32 32 37 32 43C32 37 37 32 43 32C37 32 32 27 32 21Z" fill="#FFFFFF" fill-opacity="0.6" />
  </svg>
</template>
