import { useEffect, useState, useRef } from 'react';
import './App.css';
import { SystemMonitor } from './components/SystemMonitor';
import { ImageUploader } from './components/ImageUploader'; // <--- Import this

function App() {
  const [logs, setLogs] = useState<string[]>([]);
  const [isConnected, setIsConnected] = useState(false);
  const ws = useRef<WebSocket | null>(null); 

  useEffect(() => {
    const socket = new WebSocket('ws://localhost:8080/ws');
    ws.current = socket;

    socket.onopen = () => {
      console.log('Connected to Manager Node');
      setIsConnected(true);
      socket.send('Dashboard hello!');
    };

    socket.onmessage = (event) => {
      setLogs(prev => [event.data, ...prev].slice(0, 50));
    };

    socket.onclose = () => setIsConnected(false);

    return () => socket.close();
  }, []);

  return (
    <div className="App" style={{ backgroundColor: '#1a1a1a', minHeight: '100vh', padding: '20px', color: 'white', textAlign: 'left' }}>
      <h1>Distributed Inference Dashboard</h1>
      
      {/* Grid Layout: Monitor | Uploader | Logs */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '20px' }}>
        
        {/* Left Column: Graphs */}
        <SystemMonitor />

        {/* Right Column: Actions */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
            
            {/* 1. Uploader */}
            <ImageUploader />

            {/* 2. Logs */}
            <div style={{ padding: '20px', border: '1px solid #444', borderRadius: '8px', background: '#222', flexGrow: 1, overflowY: 'auto', maxHeight: '300px' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '10px' }}>
                <h3>Live System Logs</h3>
                <span style={{ color: isConnected ? '#4caf50' : '#f44336', fontWeight: 'bold' }}>
                  {isConnected ? '● Connected' : '● Disconnected'}
                </span>
              </div>
              
              <div style={{ fontFamily: 'monospace', fontSize: '14px', color: '#ccc' }}>
                {logs.length === 0 ? (
                  <p style={{ color: '#888' }}>Waiting for logs...</p>
                ) : (
                  logs.map((log, i) => (
                    <div key={i} style={{ borderBottom: '1px solid #333', padding: '4px 0' }}>
                      {log}
                    </div>
                  ))
                )}
              </div>
            </div>
        </div>

      </div>
    </div>
  );
}

export default App;
