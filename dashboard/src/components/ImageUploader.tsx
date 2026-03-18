import React, { useState, useRef, useCallback } from "react";
import { motion, AnimatePresence } from "framer-motion";
import {
  UploadCloud,
  Loader2,
  CheckCircle,
  AlertTriangle,
  X,
  Play,
  Trash2
} from "lucide-react";

interface PredictionResult {
  label: string;
  confidence: number;
}

interface BatchItem {
  id: string;
  file: File;
  preview: string;
  status: "pending" | "uploading" | "success" | "error";
  result?: PredictionResult;
  error?: string;
}

export function ImageUploader() {
  const [items, setItems] = useState<BatchItem[]>([]);
  const [isDragging, setIsDragging] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const handleFiles = (selectedFiles: FileList | File[]) => {
    const newItems: BatchItem[] = Array.from(selectedFiles)
      .filter(file => file.type.startsWith("image/"))
      .map(file => ({
        id: Math.random().toString(36).substring(7),
        file,
        preview: URL.createObjectURL(file),
        status: "pending",
      }));

    setItems(prev => [...prev, ...newItems]);
  };

  const onDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(true);
  }, []);

  const onDragLeave = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);
  }, []);

  const onDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);
    if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
      handleFiles(e.dataTransfer.files);
    }
  }, []);

  const removeItem = (id: string) => {
    setItems(prev => prev.filter(item => item.id !== id));
  };

  const clearAll = () => {
    setItems([]);
    if (fileInputRef.current) fileInputRef.current.value = "";
  };

  const processBatch = async () => {
    const apiUrl = import.meta.env.VITE_API_URL || "http://localhost:8080";
    const pendingItems = items.filter(item => item.status === "pending" || item.status === "error");
    
    if (pendingItems.length === 0) return;

    // 1. Mark all pending items as uploading in the UI
    setItems(prev => prev.map(i => 
      pendingItems.find(p => p.id === i.id) ? { ...i, status: "uploading", error: undefined } : i
    ));

    try {
      // 2. Bundle all files into a single payload
      const formData = new FormData();
      pendingItems.forEach(item => {
        // We use the unique item.id as the field name so the backend can track it
        formData.append(item.id, item.file);
      });

      // 3. Fire the massive batch request
      const response = await fetch(`${apiUrl}/api/batch`, {
        method: "POST",
        body: formData, // The browser handles the multipart/form-data boundaries automatically!
      });

      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      
      // We expect an array back: [{ id: "abc", label: "Cat", confidence: 0.99 }, ...]
      const results = await response.json();

      // 4. Map the results back to the exact images using the ID
      setItems(prev => prev.map(item => {
        const result = results.find((r: any) => r.id === item.id);
        if (result) {
          return { ...item, status: "success", result: { label: result.label, confidence: result.confidence } };
        }
        return item; 
      }));

    } catch (err) {
      // Mark anything still uploading as failed
      setItems(prev => prev.map(i => 
        i.status === "uploading" ? { ...i, status: "error", error: err instanceof Error ? err.message : "Batch Failed" } : i
      ));
    }
  };

  const hasPending = items.some(i => i.status === "pending" || i.status === "error");

  return (
    <div className="w-full flex flex-col gap-6">
      {/* Hidden File Input */}
      <input
        type="file"
        accept="image/*"
        multiple
        ref={fileInputRef}
        onChange={(e) => e.target.files && handleFiles(e.target.files)}
        className="hidden"
      />

      {/* Header & Global Controls */}
      <div className="flex items-center justify-between">
        <div className="flex gap-3 w-full">
          <button
            onClick={() => fileInputRef.current?.click()}
            className="flex-1 py-2 bg-slate-800 hover:bg-slate-700 text-white font-semibold rounded-lg border border-slate-600 transition-colors flex items-center justify-center gap-2"
          >
            <UploadCloud className="w-4 h-4" /> Select Images
          </button>
          
          <button
            onClick={processBatch}
            disabled={!hasPending}
            className={`flex-1 py-2 font-bold rounded-lg transition-all flex items-center justify-center gap-2 shadow-lg
              ${hasPending 
                ? "bg-grid-neon text-black hover:bg-cyan-300 shadow-cyan-500/20" 
                : "bg-slate-800 text-slate-500 cursor-not-allowed border border-slate-700"}`}
          >
            <Play className="w-4 h-4" /> Execute Batch
          </button>

          {items.length > 0 && (
            <button
              onClick={clearAll}
              className="px-4 py-2 bg-slate-900 hover:bg-grid-alert/20 text-slate-400 hover:text-grid-alert rounded-lg border border-slate-700 hover:border-grid-alert transition-colors"
            >
              <Trash2 className="w-4 h-4" />
            </button>
          )}
        </div>
      </div>

      {/* Drop Zone (Only shows prominently if no items, otherwise stays as a small target) */}
      <AnimatePresence>
        {items.length === 0 && (
          <motion.div
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: "auto" }}
            exit={{ opacity: 0, height: 0, overflow: "hidden" }}
            onDragOver={onDragOver}
            onDragLeave={onDragLeave}
            onDrop={onDrop}
            className={`
              relative cursor-pointer w-full h-48 rounded-xl border-2 border-dashed 
              flex flex-col items-center justify-center transition-all duration-300
              ${isDragging ? "border-grid-neon bg-grid-neon/10 shadow-[0_0_15px_rgba(6,182,212,0.3)]" : "border-slate-600 hover:border-slate-400 hover:bg-slate-800/50"}
            `}
          >
            <UploadCloud className={`w-12 h-12 mb-3 transition-colors duration-300 ${isDragging ? "text-grid-neon" : "text-slate-400"}`} />
            <p className="text-sm font-medium text-slate-300">Drag & Drop Batch Here</p>
          </motion.div>
        )}
      </AnimatePresence>

      {/* Results Gallery Grid */}
      {items.length > 0 && (
        <motion.div layout className="grid grid-cols-2 md:grid-cols-3 gap-4 max-h-[500px] overflow-y-auto pr-2 custom-scrollbar">
          <AnimatePresence>
            {items.map((item) => (
              <motion.div
                layout
                initial={{ opacity: 0, scale: 0.8 }}
                animate={{ opacity: 1, scale: 1 }}
                exit={{ opacity: 0, scale: 0.8 }}
                key={item.id}
                className="relative rounded-xl overflow-hidden border border-slate-700 bg-black aspect-square group"
              >
                <img src={item.preview} alt="preview" className="object-cover w-full h-full opacity-50 group-hover:opacity-30 transition-opacity" />

                {/* Remove Button */}
                {(item.status === "pending" || item.status === "error") && (
                  <button
                    onClick={() => removeItem(item.id)}
                    className="absolute top-2 right-2 p-1.5 bg-black/60 hover:bg-grid-alert text-white rounded-full transition-colors z-20"
                  >
                    <X className="w-3 h-3" />
                  </button>
                )}

                {/* Status Overlay */}
                <div className="absolute inset-0 flex flex-col items-center justify-center p-3 text-center">
                  {item.status === "uploading" && (
                    <Loader2 className="w-8 h-8 text-grid-neon animate-spin" />
                  )}

                  {item.status === "error" && (
                    <div className="text-grid-alert flex flex-col items-center">
                      <AlertTriangle className="w-6 h-6 mb-1" />
                      <span className="text-[10px] font-mono leading-tight">{item.error}</span>
                    </div>
                  )}

                  {item.status === "success" && item.result && (
                    <motion.div initial={{ y: 10, opacity: 0 }} animate={{ y: 0, opacity: 1 }} className="w-full">
                      <CheckCircle className="w-6 h-6 text-grid-success mx-auto mb-1 shadow-black drop-shadow-md" />
                      <div className="bg-black/70 backdrop-blur-sm p-2 rounded-lg border border-slate-700">
                        <p className="text-xs font-bold text-white uppercase truncate">{item.result.label}</p>
                        
                        {/* Mini Confidence Bar */}
                        <div className="w-full bg-slate-800 rounded-full h-1 mt-2">
                          <motion.div
                            initial={{ width: 0 }}
                            animate={{ width: `${item.result.confidence * 100}%` }}
                            transition={{ duration: 1 }}
                            className="bg-grid-neon h-1 rounded-full shadow-[0_0_5px_rgba(6,182,212,0.8)]"
                          />
                        </div>
                        <p className="text-[9px] font-mono text-slate-400 mt-1">{(item.result.confidence * 100).toFixed(1)}%</p>
                      </div>
                    </motion.div>
                  )}
                </div>
              </motion.div>
            ))}
          </AnimatePresence>
        </motion.div>
      )}
    </div>
  );
}