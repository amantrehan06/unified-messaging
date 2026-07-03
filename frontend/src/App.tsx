import { useEffect, useState } from "react";
import {
  fetchMe,
  getToken,
  setToken,
  clearToken,
  getGoogleLoginUrl,
  type MeResponse,
} from "./api/client";

function App() {
  const [user, setUser] = useState<MeResponse | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    // Handle OAuth callback - extract token from URL
    const params = new URLSearchParams(window.location.search);
    const token = params.get("token");
    if (token) {
      setToken(token);
      window.history.replaceState({}, "", "/");
    }

    // Check if already authenticated
    if (getToken()) {
      fetchMe()
        .then(setUser)
        .catch(() => {
          clearToken();
          setUser(null);
        })
        .finally(() => setLoading(false));
    } else {
      setLoading(false);
    }
  }, []);

  if (loading) {
    return (
      <div style={{ fontFamily: "system-ui", padding: "2rem" }}>
        <p>Loading...</p>
      </div>
    );
  }

  if (!user) {
    return (
      <div style={{ fontFamily: "system-ui", padding: "2rem" }}>
        <h1>Unified Messaging</h1>
        <p>Sign in to get started.</p>
        <a
          href={getGoogleLoginUrl()}
          style={{
            display: "inline-block",
            padding: "0.75rem 1.5rem",
            background: "#4285f4",
            color: "#fff",
            borderRadius: "4px",
            textDecoration: "none",
            fontWeight: 500,
          }}
        >
          Continue with Google
        </a>
      </div>
    );
  }

  return (
    <div style={{ fontFamily: "system-ui", padding: "2rem" }}>
      <h1>Unified Messaging</h1>
      <div
        style={{
          background: "#f5f5f5",
          padding: "1rem",
          borderRadius: "8px",
          maxWidth: "400px",
        }}
      >
        <p>
          <strong>Email:</strong> {user.email}
        </p>
        <p>
          <strong>Role:</strong> {user.role}
        </p>
        <p>
          <strong>Tenant:</strong> {user.tenant_id}
        </p>
      </div>
      <button
        onClick={() => {
          clearToken();
          setUser(null);
        }}
        style={{
          marginTop: "1rem",
          padding: "0.5rem 1rem",
          cursor: "pointer",
        }}
      >
        Sign out
      </button>
    </div>
  );
}

export default App;
