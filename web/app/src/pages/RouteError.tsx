/** Keep a failed route recoverable without exposing implementation stacks in the app. */
export function RouteError() {
  return <main className="mx-auto max-w-[600px] px-6 py-12" role="alert">
    <h1 className="type-header-emphasized text-primary">Couldn’t open this page</h1>
    <p className="mt-3 type-body-tall text-secondary">Reload the page to try again, or return to Home.</p>
    <div className="mt-5 flex gap-4">
      <button className="rounded-pill bg-key-bg px-4 py-2 text-on-key" onClick={() => window.location.reload()}>Try again</button>
      <a className="px-4 py-2 text-key" href="/home">Home</a>
    </div>
  </main>;
}
