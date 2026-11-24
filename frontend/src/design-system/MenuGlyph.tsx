export function MenuGlyph({ open }: { open: boolean }) {
  return (
    <span className="menu-glyph" data-open={open} aria-hidden="true">
      <span />
      <span />
      <span />
    </span>
  );
}
