import { NewQuestionPage } from "../features/questions/NewQuestionPage";
import { QuestionPage } from "../features/questions/QuestionPage";
import { BoardList } from "../features/boards/BoardList";
import { BoardPage } from "../features/boards/BoardPage";
import { AdminBoards } from "../features/boards/AdminBoards";
import { AuthProvider, useAuth } from "../features/auth/AuthProvider";
import { AuthPage } from "../features/auth/AuthPage";
import { AuthControls } from "../features/auth/AuthControls";
import { Link, NavLink, Route, Routes, useLocation } from "react-router";
import { useEffect } from "react";
import { ConnectionCard } from "../features/connection/ConnectionCard";
import { ModerationPage } from "../features/moderation/ModerationPage";

function PageTitle() {
  const { pathname } = useLocation();

  useEffect(() => {
    document.title = `${pathname.startsWith("/moderation") ? "Report review" : pathname.startsWith("/questions/") ? "Question" : pathname.endsWith("/questions/new") ? "Ask a question" : pathname.startsWith("/boards/") ? "Board" : pathname === "/admin/boards" ? "Manage boards" : pathname === "/about" ? "About" : pathname === "/login" ? "Sign in" : pathname === "/register" ? "Join" : pathname === "/" ? "Community" : "Page not found"} · CommonBeacon`;
  }, [pathname]);
  return null;
}

function Home() {
  return (
    <>
      <section className="hero">
        <p className="eyebrow">A PLACE TO FIGURE THINGS OUT, TOGETHER</p>
        <h1>
          Shared knowledge.
          <br />
          <em>Brighter possibilities.</em>
        </h1>
        <p className="hero-description">
          Good answers start with a conversation. CommonBeacon is a shared space
          to ask, learn, and help each other find a way forward.
        </p>
        <Link className="button button-primary" to="/about">
          Get to know CommonBeacon <span aria-hidden="true">↗</span>
        </Link>
        <div className="beacon-art" aria-hidden="true">
          <div className="beacon-orbit orbit-one" />
          <div className="beacon-orbit orbit-two" />
          <div className="beacon-sun" />
          <div className="beacon-tower" />
          <div className="beacon-ground" />
        </div>
      </section>
      <div className="content-grid">
        <section
          className="conversations"
          aria-labelledby="conversations-heading"
        >
          <div className="section-heading">
            <div>
              <p className="eyebrow">THE COMMUNITY</p>
              <h2 id="conversations-heading">Explore the boards</h2>
            </div>
            <span className="subtle-badge">Getting started</span>
          </div>
          <BoardList />
          <div className="community-principle">
            <span aria-hidden="true">✧</span>
            <p>
              You don't need to know everything.
              <br />
              <strong>A little shared experience can make a difference.</strong>
            </p>
          </div>
        </section>
        <aside>
          <ConnectionCard />
          <section className="welcome-note">
            <p className="eyebrow">OUR COMMON GROUND</p>
            <h2>Curiosity belongs here.</h2>
            <p>
              Ask with an open mind. Share what you know. Leave a little light
              for the next person.
            </p>
          </section>
        </aside>
      </div>
    </>
  );
}

function About() {
  return (
    <section className="about-page">
      <p className="eyebrow">ABOUT COMMONBEACON</p>
      <h1>
        Better answers begin
        <br />
        with <em>each other.</em>
      </h1>
      <p className="hero-description">
        A customer-support community built around a simple idea: what one person
        learns can help many others.
      </p>
      <div className="principles">
        <article>
          <span>01</span>
          <h2>Bring a question</h2>
          <p>
            Make room for the things you haven't figured out yet. A clear
            question is a useful beginning.
          </p>
        </article>
        <article>
          <span>02</span>
          <h2>Share your experience</h2>
          <p>
            Offer a helpful explanation, a fresh perspective, or a small detail
            that makes something click.
          </p>
        </article>
        <article>
          <span>03</span>
          <h2>Make answers useful</h2>
          <p>
            Help future visitors find the reply that solved a problem, so
            knowledge keeps working for others.
          </p>
        </article>
      </div>
      <p className="availability-note">
        CommonBeacon is in its early stages. Member accounts are open. Questions
        are open. Replies and accepted solutions are coming next.
      </p>
      <Link className="text-link" to="/">
        ← Back to the community
      </Link>
    </section>
  );
}

function NotFound() {
  return (
    <section className="about-page">
      <p className="eyebrow">404 · PAGE NOT FOUND</p>
      <h1>
        A different
        <br />
        <em>way forward.</em>
      </h1>
      <p className="hero-description">
        We couldn't find that page. The community home is a good place to start.
      </p>
      <Link className="button button-primary" to="/">
        Back to the community
      </Link>
    </section>
  );
}

function Shell() {
  const { expired, user } = useAuth();
  const { pathname } = useLocation();

  return (
    <div className="app-shell">
      <PageTitle />
      <a className="skip-link" href="#main-content">
        Skip to content
      </a>
      <aside className="sidebar">
        <Link to="/" className="brand" aria-label="CommonBeacon home">
          <img src="/beacon.svg" alt="" width="36" height="36" />
          <span>
            Common<span className="brand-light">Beacon</span>
          </span>
        </Link>
        <p className="sidebar-label">YOUR SHARED SPACE</p>
        <nav aria-label="Main navigation">
          <NavLink to="/" end>
            <span aria-hidden="true">◈</span> Community
          </NavLink>
          <NavLink to="/about">
            <span aria-hidden="true">◎</span> About this space
          </NavLink>
          {user?.role === "ADMINISTRATOR" && (
            <NavLink to="/admin/boards">Manage boards</NavLink>
          )}
          {(user?.role === "MODERATOR" || user?.role === "ADMINISTRATOR") && (
            <NavLink to="/moderation">Report review</NavLink>
          )}
        </nav>
        <div className="sidebar-note">
          <span className="small-beacon" aria-hidden="true">
            ✧
          </span>
          <p>
            A little knowledge.
            <br />A little generosity.
            <br />
            <strong>A brighter community.</strong>
          </p>
        </div>
        <div className="sidebar-footer">
          Built for the questions
          <br />
          we can answer together.
        </div>
      </aside>
      <div className="workspace">
        <header className="topbar">
          <span>
            Our community{" "}
            <span aria-hidden="true" className="breadcrumb-slash">
              /
            </span>{" "}
            <strong>
              {pathname === "/about"
                ? "About"
                : pathname === "/"
                  ? "Overview"
                  : pathname === "/login"
                    ? "Sign in"
                    : pathname === "/register"
                      ? "Join"
                      : pathname.startsWith("/questions/")
                        ? "Question"
                        : pathname.endsWith("/questions/new")
                          ? "Ask a question"
                          : pathname.startsWith("/boards/")
                            ? "Board"
                            : pathname === "/admin/boards"
                              ? "Manage boards"
                              : pathname.startsWith("/moderation") ? "Report review" : "Not found"}
            </strong>
          </span>
          <AuthControls />
        </header>
        <main id="main-content" tabIndex={-1}>
          {expired && (
            <p className="form-error" role="alert">
              Your session expired. Please sign in again.
            </p>
          )}
          <Routes>
            <Route path="/" element={<Home />} />
            <Route path="/about" element={<About />} />
            <Route path="/boards/:boardId" element={<BoardPage />} />
            <Route path="/admin/boards" element={<AdminBoards />} />
            <Route path="/moderation" element={<ModerationPage />} />
            <Route path="/moderation/reports/:reportId" element={<ModerationPage />} />
            <Route
              path="/boards/:boardId/questions/new"
              element={<NewQuestionPage />}
            />
            <Route path="/questions/:questionId" element={<QuestionPage />} />
            <Route
              path="/questions/:questionId/edit"
              element={<QuestionPage editing />}
            />
            <Route
              path="/login"
              element={<AuthPage key="login" mode="login" />}
            />
            <Route
              path="/register"
              element={<AuthPage key="register" mode="register" />}
            />
            <Route path="*" element={<NotFound />} />
          </Routes>
        </main>
        <footer className="page-footer">
          <span>CommonBeacon</span>
          <span>Find your way. Help someone find theirs.</span>
        </footer>
      </div>
    </div>
  );
}

export function App() {
  return (
    <AuthProvider>
      <Shell />
    </AuthProvider>
  );
}
