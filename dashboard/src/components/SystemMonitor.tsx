import React, { useState, useEffect } from 'react';
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer } from 'recharts';

// Mock data to simulate our future system state
const initialData = [
  { name: 'Manager Node', load: 40, status: 'Active' },
  { name: 'Worker A', load: 85, status: 'Busy' },
  { name: 'Worker B', load: 12, status: 'Idle' },
];

export const SystemMonitor = () => {
  const [data, setData] = useState(initialData);

  // Simulate live updates every 2 seconds
  useEffect(() => {
    const interval = setInterval(() => {
      setData(prevData => prevData.map(node => ({
        ...node,
        load: Math.max(10, Math.min(100, node.load + (Math.random() * 20 - 10)))
      })));
    }, 2000);

    return () => clearInterval(interval);
  }, []);

  return (
    <div style={{ width: '100%', height: 400, padding: '20px', background: '#222', borderRadius: '8px' }}>
      <h2 style={{ color: 'white' }}>System Load Monitor</h2>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data}>
          <CartesianGrid strokeDasharray="3 3" stroke="#444" />
          <XAxis dataKey="name" stroke="#ccc" />
          <YAxis stroke="#ccc" />
          <Tooltip contentStyle={{ backgroundColor: '#333', border: 'none' }} itemStyle={{ color: '#fff' }} />
          <Legend />
          <Bar dataKey="load" fill="#8884d8" name="CPU Load %" />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
};
