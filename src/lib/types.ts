export interface FileNode {
  name: string;
  path: string;
  isDirectory: boolean;
  isOpen?: boolean;
  children?: FileNode[];
}

export interface TabItem {
  name: string;
  path: string;
  content: string;
  isDirty: boolean;
}

export type AceTheme = 
  | 'one_dark'
  | 'monokai' 
  | 'dracula' 
  | 'nord_dark' 
  | 'gruvbox' 
  | 'tomorrow_night';

export type EditorFont = 
  | "'Fira Code', monospace" 
  | "'JetBrains Mono', monospace" 
  | "monospace";

export interface EditorSettings {
  theme: AceTheme;
  font: EditorFont;
  fontSize: number;
  tabSize: number;
  lineWrapping: boolean;
  vimEnabled: boolean;
  keyboardHandler: 'vscode' | 'vim';
}
