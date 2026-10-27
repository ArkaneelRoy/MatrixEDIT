import { registerPlugin } from '@capacitor/core';

export interface SshBridge {
  connect(options: { host: string; port: number; username: string; password?: string }): Promise<void>;
  disconnect(): Promise<void>;
  openTerminalSession(options: { id: string }): Promise<void>;
  sendTerminalInput(options: { id: string; data: string }): Promise<void>;
  closeTerminalSession(options: { id: string }): Promise<void>;
  readDir(options: { path: string }): Promise<{ files: Array<{ name: string; isDirectory: boolean }> }>;
  readFile(options: { path: string }): Promise<{ content: string }>;
  writeFile(options: { path: string; content: string }): Promise<{ success: boolean; bytesWritten: number }>;
  createFile(options: { path: string }): Promise<void>;
  mkdir(options: { path: string }): Promise<void>;
  rename(options: { oldPath: string; newPath: string }): Promise<void>;
  delete(options: { path: string; isDirectory: boolean }): Promise<void>;
  addListener(eventName: 'terminalData', listenerFunc: (data: { id: string; chunk: string }) => void): Promise<any>;
}

export const Ssh = registerPlugin<SshBridge>('SshBridge');
