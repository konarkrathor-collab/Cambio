// A titled panel with the four states every panel needs: loading, error, empty, content.

export default function Panel({ title, actions, className = '', children, id }) {
  const headingId = id ? `${id}-title` : undefined;
  return (
    <section className={`panel ${className}`} aria-labelledby={headingId}>
      <header className="panel-head">
        <h2 id={headingId}>{title}</h2>
        {actions ? <div className="panel-actions">{actions}</div> : null}
      </header>
      <div className="panel-body">{children}</div>
    </section>
  );
}

/** Loading / error / empty placeholder text for a panel body. */
export function PanelState({ kind, children }) {
  return (
    <p className={`panel-state panel-state-${kind}`} role={kind === 'error' ? 'alert' : undefined}>
      {children}
    </p>
  );
}
