import { useId } from 'react';
import { ChoiceInput } from './ChoiceInput';
import styles from './AuthorizationSwitch.module.css';

type Props = {
  label: string;
  status: string;
  checked: boolean;
  pending: boolean;
  summary: string;
  agreement: string;
  agreed: boolean;
  onAgree: (agreed: boolean) => void;
  onToggle: (enabled: boolean) => void;
};

export function AuthorizationSwitch({
  label,
  status,
  checked,
  pending,
  summary,
  agreement,
  agreed,
  onAgree,
  onToggle,
}: Props) {
  const description = useId();

  return (
    <div className={styles.control}>
      <label className={styles.row}>
        <span>{label}</span>
        <ChoiceInput
          variant="switch"
          role="switch"
          aria-describedby={description}
          checked={checked}
          disabled={pending || (!checked && !agreed)}
          onChange={(event) => onToggle(event.target.checked)}
        />
      </label>
      <p className={styles.status} role="status">
        {status}
      </p>
      <p className={styles.summary} id={description}>
        {summary}
      </p>
      {!checked && (
        <label className={styles.agreement}>
          <ChoiceInput
            checked={agreed}
            disabled={pending}
            onChange={(event) => onAgree(event.target.checked)}
          />
          <span>{agreement}</span>
        </label>
      )}
    </div>
  );
}
