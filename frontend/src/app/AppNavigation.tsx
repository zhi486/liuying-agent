import { ChatCircleDots, PersonSimpleRun } from '@phosphor-icons/react';
import { Link, useLocation } from 'react-router-dom';

export function AppNavigation() {
  const isAgentPage = useLocation().pathname === '/agent';
  const target = isAgentPage ? '/run' : '/agent';
  const label = isAgentPage ? '跑步' : '对话';
  const Icon = isAgentPage ? PersonSimpleRun : ChatCircleDots;

  return (
    <nav className="app-navigation" aria-label="页面切换">
      <Link to={target} className="view-switch" aria-label={`切换到${label}`}>
        <Icon size={17} weight="regular" aria-hidden="true" />
        <span>{label}</span>
      </Link>
    </nav>
  );
}
