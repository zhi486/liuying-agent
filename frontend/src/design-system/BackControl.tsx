import type { ComponentPropsWithRef } from 'react';
import { ArrowLeft } from '@phosphor-icons/react';
import { Link, type LinkProps } from 'react-router-dom';
import styles from './BackControl.module.css';

type Appearance = { label: string; inverse?: boolean; sticky?: boolean };
type BackLinkProps = Omit<LinkProps, 'children' | 'title' | 'aria-label'> & Appearance;
type BackButtonProps = Omit<ComponentPropsWithRef<'button'>, 'children' | 'title' | 'aria-label'> &
  Appearance;

export function BackLink({ label, inverse, sticky, className = '', ...props }: BackLinkProps) {
  return (
    <Link
      {...props}
      className={`${styles.control} ${className}`}
      aria-label={label}
      title={label}
      data-inverse={inverse || undefined}
      data-sticky={sticky || undefined}
    >
      <ArrowLeft aria-hidden="true" />
    </Link>
  );
}

export function BackButton({ label, inverse, sticky, className = '', ...props }: BackButtonProps) {
  return (
    <button
      type="button"
      {...props}
      className={`${styles.control} ${className}`}
      aria-label={label}
      title={label}
      data-inverse={inverse || undefined}
      data-sticky={sticky || undefined}
    >
      <ArrowLeft aria-hidden="true" />
    </button>
  );
}
