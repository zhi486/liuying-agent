import styles from './LoadingSkeleton.module.css';

export function LoadingSkeleton({ label }: { label: string }) {
  return (
    <div className={styles.root} role="status" aria-label={label}>
      <div aria-hidden="true" className={styles.rows}>
        {[0, 1, 2].map((row) => (
          <div key={row}>
            <span />
            <span />
            <span />
          </div>
        ))}
      </div>
      <p>{label}</p>
    </div>
  );
}
