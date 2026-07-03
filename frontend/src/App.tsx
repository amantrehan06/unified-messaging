import { BrowserRouter, Routes, Route, Navigate } from 'react-router';
import { useAuth, AuthProvider } from './auth/AuthContext';
import { LoginPage } from './pages/LoginPage';
import { InboxPage } from './pages/InboxPage';
import { HomePage } from './pages/HomePage';

function AppShell() {
  const { user, loading } = useAuth();

  if (loading) {
    return (
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100vh', color: 'var(--ink-3)' }}>
        Loading...
      </div>
    );
  }

  return (
    <Routes>
      <Route
        path="/"
        element={user ? <Navigate to="/inbox" replace /> : <HomePage />}
      />
      <Route
        path="/login"
        element={user ? <Navigate to="/inbox" replace /> : <LoginPage />}
      />
      <Route
        path="/inbox"
        element={user ? <InboxPage /> : <Navigate to="/login" replace />}
      />
    </Routes>
  );
}

function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <AppShell />
      </AuthProvider>
    </BrowserRouter>
  );
}

export default App;
