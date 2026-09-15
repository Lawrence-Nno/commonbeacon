import { useQuery } from "@tanstack/react-query";
import { Button } from "../../components/Button";
import { ApiError } from "../../lib/http";
import { getHealth } from "./health";

export function ConnectionCard() {
  const health = useQuery({
    queryKey: ["health"],
    queryFn: ({ signal }) => getHealth(signal),
    retry: false,
    refetchInterval: 30_000,
    refetchIntervalInBackground: false,
  });
  // A failed refresh must supersede previously cached success.
  const state = health.isError
    ? "offline"
    : health.data
      ? "online"
      : "checking";
  const title =
    state === "online"
      ? "A light is on."
      : state === "offline"
        ? "Connection interrupted"
        : "Checking the connection…";

  return (
    <section
      className={`connection-card ${state}`}
      id="connection"
      aria-labelledby="connection-heading"
    >
      <div className="section-label">
        <span className="status-dot" /> COMMUNITY CONNECTION
      </div>
      <div role="status" aria-live="polite">
        <h2 id="connection-heading">{title}</h2>
        <p>
          {state === "online"
            ? "The community service is available. A shared space is taking shape."
            : state === "offline"
              ? health.error instanceof ApiError
                ? health.error.message
                : "Please try connecting again."
              : "Making sure we can reach the community service."}
        </p>
      </div>
      <Button
        className="button-secondary"
        onClick={() => {
          void health.refetch();
        }}
        disabled={health.isFetching}
      >
        {health.isFetching
          ? "Checking…"
          : state === "offline"
            ? "Try again"
            : "Check connection"}
      </Button>
      <span className="connection-note">
        Checked automatically every 30 seconds while this tab is active.
      </span>
    </section>
  );
}
