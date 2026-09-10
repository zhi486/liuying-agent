export function formatPace(paceSecPerKm: number | null | undefined): string {
  if (paceSecPerKm == null || paceSecPerKm <= 0) return '--';
  const roundedSeconds = Math.round(paceSecPerKm);
  const minutes = Math.floor(roundedSeconds / 60);
  const seconds = roundedSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, '0')}`;
}
