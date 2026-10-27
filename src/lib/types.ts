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

export type EditorTheme = 
  | 'oneDark' 
  | 'dracula' 
  | 'tokyoNight' 
  | 'nord' 
  | 'githubDark' 
  | 'materialDark';

export type EditorFont = 
  | "'Fira Code', monospace" 
  | "'JetBrains Mono', monospace" 
  | "monospace";

export interface EditorSettings {
  theme: EditorTheme;
  font: EditorFont;
  fontSize: number;
  tabSize: number;
  lineWrapping: boolean;
  vimEnabled: boolean;
}
