import React, { useState } from 'react';

export const ImageUploader = () => {
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [result, setResult] = useState<{ label: string; confidence: number } | null>(null);
  const [loading, setLoading] = useState(false);

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files[0]) {
      const file = e.target.files[0];
      setSelectedFile(file);
      setPreviewUrl(URL.createObjectURL(file));
      setResult(null); // Reset previous result
    }
  };

  const handleUpload = async () => {
    if (!selectedFile) return;

    setLoading(true);
    try {
      // 1. Prepare the raw bytes (or form data)
      const response = await fetch('http://localhost:8080/api/job', {
        method: 'POST',
        body: selectedFile, // Sending raw bytes directly
        headers: {
            'Content-Type': 'application/octet-stream'
        }
      });

      if (!response.ok) {
        throw new Error(`Server error: ${response.status}`);
      }

      // 2. Parse the JSON result from Java
      const data = await response.json();
      setResult(data);

    } catch (error) {
      console.error("Upload failed", error);
      alert("Inference failed! Is the Java Manager running?");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ padding: '20px', border: '1px solid #444', borderRadius: '8px', background: '#222', color: 'white' }}>
      <h3>Submit Job</h3>
      
      {/* File Input */}
      <input type="file" accept="image/*" onChange={handleFileChange} style={{ marginBottom: '15px' }} />

      {/* Image Preview */}
      {previewUrl && (
        <div style={{ marginBottom: '15px' }}>
          <img src={previewUrl} alt="Preview" style={{ maxWidth: '100%', maxHeight: '200px', borderRadius: '4px' }} />
        </div>
      )}

      {/* Submit Button */}
      <button 
        onClick={handleUpload} 
        disabled={!selectedFile || loading}
        style={{
          padding: '10px 20px',
          backgroundColor: loading ? '#555' : '#4caf50',
          color: 'white',
          border: 'none',
          borderRadius: '4px',
          cursor: loading ? 'not-allowed' : 'pointer'
        }}
      >
        {loading ? 'Processing...' : 'Run Inference'}
      </button>

      {/* Result Display */}
      {result && (
        <div style={{ marginTop: '20px', padding: '10px', backgroundColor: '#333', borderRadius: '4px' }}>
          <h4 style={{ margin: '0 0 5px 0', color: '#4caf50' }}>Prediction: {result.label}</h4>
          <p style={{ margin: 0 }}>Confidence: {(result.confidence * 100).toFixed(2)}%</p>
        </div>
      )}
    </div>
  );
};
