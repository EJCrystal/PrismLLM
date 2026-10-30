/**
 * Coordinate usage-report loading with retention-policy updates.
 * Report requests stay valid across a toggle; only an older retention snapshot is discarded.
 */
export function createRetentionReportState() {
  let retentionVersion = 0
  return {
    beginLoad() {
      return retentionVersion
    },
    shouldApplyRetention(loadVersion: number): boolean {
      return loadVersion === retentionVersion
    },
    beginToggle() {
      retentionVersion += 1
    },
    endToggle() {
      retentionVersion += 1
    },
  }
}
