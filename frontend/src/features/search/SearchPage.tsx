import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router";
import { ApiError } from "../../lib/http";
import { search } from "./api";

function SearchForm({ query, submit }: { query: string; submit: (q: string) => void }) {
  const [text, setText] = useState(query);
  return <form role="search" className="search-form" onSubmit={(event) => { event.preventDefault(); submit(text.trim()); }}>
    <label htmlFor="search-query">Search titles and bodies</label>
    <div><input id="search-query" type="search" value={text} onChange={(event) => setText(event.target.value)} aria-describedby="search-help" />
      <button className="button button-primary" type="submit">Search</button></div>
    <p id="search-help">Search English words in questions and articles. Use quotes for phrases, OR for alternatives, and - to exclude a word. Up to 200 characters.</p>
  </form>;
}

export function SearchPage() {
  const [params, setParams] = useSearchParams();
  const rawQuery = params.get("q") ?? "", q = rawQuery.trim(), rawPage = params.get("page") ?? "0";
  const page = /^\d+$/.test(rawPage) ? Number(rawPage) : -1;
  const validPage = Number.isSafeInteger(page) && page >= 0 && page * 20 <= 2147483647;
  const validation = q.length > 200 ? "Use at most 200 characters after trimming." : !validPage ? "Invalid search page. Start from the first page." : "";
  const results = useQuery({ queryKey: ["search", q, page], queryFn: ({ signal }) => search(q, page, signal),
    enabled: !validation && !!q, retry: false, staleTime: 0 });
  const changePage = (page: number) => setParams({ q, page: String(page) });
  const error = results.error;
  const terminal = error instanceof ApiError && [400, 401, 403, 404].includes(error.status ?? 0);
  return <section className="board-page"><p className="eyebrow">COMMUNITY KNOWLEDGE</p><h1>Search the community</h1>
    <SearchForm key={rawQuery} query={rawQuery} submit={(next) => {
      if (next === q && page === 0 && next && !validation) void results.refetch();
      else setParams({ q: next, page: "0" });
    }} />
    {validation ? <p className="form-error" role="alert">{validation} {!validPage && <button className="text-link" onClick={() => changePage(0)}>First page</button>}</p>
      : !q ? <p>Enter words to search questions and articles.</p>
      : results.isPending ? <p role="status">Searching...</p>
      : results.isError ? <div role="alert" className="form-error"><p>{error instanceof ApiError && error.fieldErrors?.q ? error.fieldErrors.q : results.error.message}</p>
        {!terminal && <button className="button button-secondary" disabled={results.isFetching} onClick={() => void results.refetch()}>Retry search</button>}</div>
      : <><p role="status">{results.data.totalElements} results for “{q}”</p>
        {results.data.items.length === 0 ? <p className="empty-state">No matching results on this page.</p>
          : <ol className="moderation-list">{results.data.items.map((hit) => <li className="reply-card" key={`${hit.kind}:${hit.id}`}>
            <p className="eyebrow">{hit.kind === "ARTICLE" ? "Knowledge article" : "Community question"}</p>
            <h2><Link className="text-link" to={hit.url}>{hit.title}</Link></h2><p className="search-snippet">{hit.snippet}</p>
          </li>)}</ol>}
        {(results.data.totalPages > 1 || page > 0) && <nav className="pagination" aria-label="Search result pages">
          <button className="button button-secondary" disabled={page === 0} onClick={() => changePage(page - 1)}>Previous results</button>
          <span>Page {page + 1} of {Math.max(1, results.data.totalPages)}</span>
          <button className="button button-secondary" disabled={page + 1 >= results.data.totalPages} onClick={() => changePage(page + 1)}>Next results</button>
          {page >= results.data.totalPages && <button className="text-link" onClick={() => changePage(0)}>First page</button>}
        </nav>}
      </>}
    <p>Results are ordered by relevance, then content type and ID. English stemming is applied; typo correction is not supported.</p>
  </section>;
}
