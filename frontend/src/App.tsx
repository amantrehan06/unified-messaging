import { useEffect, useState } from "react";
import {
  fetchMe,
  getToken,
  setToken,
  clearToken,
  getGoogleLoginUrl,
  fetchChannels,
  connectChannel,
  reconnectChannel,
  type MeResponse,
  type ChannelResponse,
} from "./api/client";

function App() {
  const [user, setUser] = useState<MeResponse | null>(null);
  const [channels, setChannels] = useState<ChannelResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [connecting, setConnecting] = useState(false);
  const [statusMessage, setStatusMessage] = useState<string | null>(null);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);

    // Handle OAuth callback
    const token = params.get("token");
    if (token) {
      setToken(token);
      window.history.replaceState({}, "", window.location.pathname);
    }

    // Handle channel connection callback
    const channelStatus = params.get("status");
    if (channelStatus === "success") {
      setStatusMessage("Channel connected successfully!");
      window.history.replaceState({}, "", window.location.pathname);
    } else if (channelStatus === "failure") {
      setStatusMessage("Channel connection failed. Please try again.");
      window.history.replaceState({}, "", window.location.pathname);
    }

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

  useEffect(() => {
    if (user) {
      fetchChannels().then(setChannels).catch(console.error);
    }
  }, [user]);

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

  async function handleConnect(type: string) {
    setConnecting(true);
    setStatusMessage(null);
    try {
      const { url } = await connectChannel(type);
      window.location.href = url;
    } catch (err) {
      setStatusMessage("Failed to start connection. Please try again.");
      setConnecting(false);
    }
  }

  async function handleReconnect(channelId: string) {
    setConnecting(true);
    setStatusMessage(null);
    try {
      const { url } = await reconnectChannel(channelId);
      window.location.href = url;
    } catch (err) {
      setStatusMessage("Failed to start reconnection. Please try again.");
      setConnecting(false);
    }
  }

  return (
    <div style={{ fontFamily: "system-ui", padding: "2rem", maxWidth: "600px" }}>
      <h1>Unified Messaging</h1>

      <div style={{ background: "#f5f5f5", padding: "1rem", borderRadius: "8px", marginBottom: "1.5rem" }}>
        <p><strong>Email:</strong> {user.email}</p>
        <p><strong>Role:</strong> {user.role}</p>
        <p><strong>Tenant:</strong> {user.tenant_id}</p>
      </div>

      {statusMessage && (
        <div style={{
          padding: "0.75rem 1rem",
          marginBottom: "1rem",
          borderRadius: "4px",
          background: statusMessage.includes("success") ? "#e8f5e9" : "#ffebee",
          color: statusMessage.includes("success") ? "#2e7d32" : "#c62828",
        }}>
          {statusMessage}
        </div>
      )}

      <h2>Channels</h2>

      {channels.length > 0 && (
        <div style={{ marginBottom: "1.5rem" }}>
          {channels.map((ch) => (
            <div key={ch.id} style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              padding: "0.75rem 1rem",
              marginBottom: "0.5rem",
              background: "#fafafa",
              border: "1px solid #e0e0e0",
              borderRadius: "6px",
            }}>
              <div>
                <strong>{ch.type}</strong>
                <StatusChip status={ch.status} />
              </div>
              {ch.status === "error" && (
                <button
                  onClick={() => handleReconnect(ch.id)}
                  disabled={connecting}
                  style={{
                    padding: "0.4rem 0.8rem",
                    background: "#ff9800",
                    color: "#fff",
                    border: "none",
                    borderRadius: "4px",
                    cursor: connecting ? "not-allowed" : "pointer",
                  }}
                >
                  Reconnect
                </button>
              )}
            </div>
          ))}
        </div>
      )}

      <h3>Connect a channel</h3>
      <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap" }}>
        {["whatsapp", "instagram"].map((type) => (
          <button
            key={type}
            onClick={() => handleConnect(type)}
            disabled={connecting}
            style={{
              padding: "0.6rem 1.2rem",
              background: "#1976d2",
              color: "#fff",
              border: "none",
              borderRadius: "4px",
              cursor: connecting ? "not-allowed" : "pointer",
              textTransform: "capitalize",
            }}
          >
            Connect {type}
          </button>
        ))}
      </div>

      <button
        onClick={() => { clearToken(); setUser(null); }}
        style={{ marginTop: "2rem", padding: "0.5rem 1rem", cursor: "pointer" }}
      >
        Sign out
      </button>
    </div>
  );
}

function StatusChip({ status }: { status: string }) {
  const colors: Record<string, { bg: string; text: string }> = {
    connected: { bg: "#e8f5e9", text: "#2e7d32" },
    pending: { bg: "#fff3e0", text: "#e65100" },
    error: { bg: "#ffebee", text: "#c62828" },
    disabled: { bg: "#f5f5f5", text: "#757575" },
  };
  const c = colors[status] ?? colors.disabled;
  return (
    <span style={{
      display: "inline-block",
      marginLeft: "0.5rem",
      padding: "0.15rem 0.5rem",
      borderRadius: "12px",
      fontSize: "0.8rem",
      background: c.bg,
      color: c.text,
    }}>
      {status}
    </span>
  );
}

export default App;
