/** Find the price record for one channel/model pair. */
export function findModelPrice(prices, channelId, upstreamModel) {
  if (!Array.isArray(prices) || !channelId || !upstreamModel) return null
  return prices.find((price) =>
    price.channelId === channelId && price.upstreamModel === upstreamModel,
  ) || null
}
