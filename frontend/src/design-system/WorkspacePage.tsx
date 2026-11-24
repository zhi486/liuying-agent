import type { Icon } from '@phosphor-icons/react';
import { CaretRight, Info } from '@phosphor-icons/react';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';

import styles from './WorkspacePage.module.css';

interface WorkspacePageProps {
  title: string;
  description?: string;
  meta?: ReactNode;
  children: ReactNode;
  className?: string;
  labelledBy?: string;
}

export function WorkspacePage({
  title,
  description,
  meta,
  children,
  className,
  labelledBy,
}: WorkspacePageProps) {
  return (
    <section className={`${styles.page} ${className ?? ''}`} aria-labelledby={labelledBy}>
      <header className={styles.header}>
        <div className={styles.heading}>
          <h1>{title}</h1>
          {description ? <p>{description}</p> : null}
        </div>
        {meta ? <div className={styles.meta}>{meta}</div> : null}
      </header>
      <div className={styles.stack}>{children}</div>
    </section>
  );
}

interface WorkspacePanelProps {
  title?: string;
  description?: string;
  icon?: Icon;
  action?: ReactNode;
  children: ReactNode;
  className?: string;
  label?: string;
  flush?: boolean;
}

export function WorkspacePanel({
  title,
  description,
  icon: PanelIcon,
  action,
  children,
  className,
  label,
  flush = false,
}: WorkspacePanelProps) {
  return (
    <section className={`${styles.panel} ${className ?? ''}`} aria-label={label}>
      {title ? (
        <header className={styles.panelHeader}>
          {PanelIcon ? (
            <span className={styles.iconTile} aria-hidden="true">
              <PanelIcon size={20} weight="regular" />
            </span>
          ) : null}
          <div className={styles.panelHeading}>
            <h2>{title}</h2>
            {description ? <p>{description}</p> : null}
          </div>
          {action ? <div className={styles.panelAction}>{action}</div> : null}
        </header>
      ) : null}
      <div className={flush ? styles.panelBodyFlush : styles.panelBody}>{children}</div>
    </section>
  );
}

interface WorkspaceNavItemProps {
  title: string;
  description?: string;
  icon: Icon;
  to?: string;
  state?: unknown;
  meta?: ReactNode;
  onClick?: () => void;
}

export function WorkspaceNavItem({
  title,
  description,
  icon: ItemIcon,
  to,
  state,
  meta,
  onClick,
}: WorkspaceNavItemProps) {
  const content = (
    <>
      <span className={styles.navIcon} aria-hidden="true">
        <ItemIcon size={20} weight="regular" />
      </span>
      <span className={styles.navCopy}>
        <strong>{title}</strong>
        {description ? <small>{description}</small> : null}
      </span>
      {meta ? <span className={styles.navMeta}>{meta}</span> : null}
      <CaretRight className={styles.navCaret} size={16} aria-hidden="true" />
    </>
  );

  if (to) {
    return (
      <Link className={styles.navItem} to={to} state={state}>
        {content}
      </Link>
    );
  }

  return (
    <button className={styles.navItem} type="button" onClick={onClick}>
      {content}
    </button>
  );
}

interface WorkspaceNoticeProps {
  children: ReactNode;
  title?: string;
  tone?: 'info' | 'warning' | 'danger';
}

export function WorkspaceNotice({ children, title, tone = 'info' }: WorkspaceNoticeProps) {
  return (
    <aside className={`${styles.notice} ${styles[`notice_${tone}`]}`}>
      <Info className={styles.noticeIcon} size={18} weight="fill" aria-hidden="true" />
      <div>
        {title ? <strong>{title}</strong> : null}
        <div>{children}</div>
      </div>
    </aside>
  );
}
