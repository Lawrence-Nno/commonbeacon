import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
} from "react";
import type { ReactNode } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { currentUser } from "./api";
import type { User } from "./api";

type AuthState = {
  user: User | null | undefined;
  sessionError: boolean;
  expired: boolean;
  setSession: (user: User | null) => Promise<void>;
};
const AuthContext = createContext<AuthState | undefined>(undefined);
export function AuthProvider({ children }: { children: ReactNode }) {
  const client = useQueryClient();
  const [user, setUser] = useState<User | null>();
  const [sessionError, setSessionError] = useState(false);
  const [expired, setExpired] = useState(false);
  const previous = useRef<User | null>(null);
  const generation = useRef(0);

  const setSession = useCallback(
    async (next: User | null) => {
      generation.current++;
      await client.cancelQueries();
      client.clear();
      previous.current = next;
      setUser(next);
      setExpired(false);
      setSessionError(false);
    },
    [client],
  );

  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    async function refresh() {
      const currentGeneration = generation.current;
      try {
        const next = await currentUser(controller.signal);
        if (!active || currentGeneration !== generation.current) return;
        if (previous.current?.id !== next?.id) {
          await client.cancelQueries();
          client.clear();
          if (!active || currentGeneration !== generation.current) return;
          if (previous.current && !next) setExpired(true);
        }
        previous.current = next;
        setUser(next);
        setSessionError(false);
      } catch {
        if (active && currentGeneration === generation.current)
          setSessionError(true);
      }
    }
    function expire() {
      if (!previous.current) return;
      void setSession(null).then(() => {
        if (active) setExpired(true);
      });
    }
    function onFocus() {
      void refresh();
    }
    void refresh();
    const timer = window.setInterval(() => {
      if (document.visibilityState === "visible") void refresh();
    }, 60_000);
    window.addEventListener("focus", onFocus);
    window.addEventListener("commonbeacon:session-expired", expire);
    return () => {
      active = false;
      controller.abort();
      window.clearInterval(timer);
      window.removeEventListener("focus", onFocus);
      window.removeEventListener("commonbeacon:session-expired", expire);
    };
  }, [client, setSession]);

  return (
    <AuthContext.Provider value={{ user, sessionError, expired, setSession }}>
      {children}
    </AuthContext.Provider>
  );
}
// eslint-disable-next-line react-refresh/only-export-components
export function useAuth() {
  const auth = useContext(AuthContext);
  if (!auth) throw new Error("AuthProvider is required");
  return auth;
}
