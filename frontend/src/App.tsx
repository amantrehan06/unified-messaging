import { useEffect, useState } from "react";
import { fetchHealth } from "./api/client";

function App() {
  const [status, setStatus] = useState<string>("loading...");

  useEffect(() => {
    fetchHealth()
      .then((data) => setStatus(`backend: ${data.status}`))
      .catch(() => setStatus("backend: unreachable"));
  }, []);

  return (
    <div style={{ fontFamily: "system-ui", padding: "2rem" }}>
      <h1>Unified Messaging</h1>
      <p>{status}</p>
    </div>
  );
}

export default App;
