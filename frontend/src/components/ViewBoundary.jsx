import { Component } from 'react';

/**
 * Catches a render error in one view and shows a recoverable message in its place; the
 * other views, the top bar and the stream keep working. "Try again" re-renders the view.
 */
export default class ViewBoundary extends Component {
  constructor(props) {
    super(props);
    this.state = { error: null };
    this.retry = () => this.setState({ error: null });
  }

  static getDerivedStateFromError(error) {
    return { error };
  }

  componentDidUpdate(prev) {
    // switching to another view clears the error of the one left behind
    if (this.state.error && prev.resetKey !== this.props.resetKey) this.retry();
  }

  render() {
    if (!this.state.error) return this.props.children;
    const message = this.state.error && this.state.error.message ? this.state.error.message : String(this.state.error);
    return (
      <section className="panel view-error" role="alert">
        <header className="panel-head"><h2>{this.props.name} view failed</h2></header>
        <div className="panel-body">
          <p>This view hit an error and stopped drawing. The other views and the live stream are unaffected.</p>
          <p className="num muted">{message}</p>
          <button type="button" className="btn" onClick={this.retry}>Try again</button>
        </div>
      </section>
    );
  }
}
