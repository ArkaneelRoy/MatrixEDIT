<script lang="ts">
  import { onMount, onDestroy } from 'svelte';
  import ace from 'ace-builds';

  // Import base ace modes & themes bundled by Vite
  import 'ace-builds/src-noconflict/ext-language_tools';
  import 'ace-builds/src-noconflict/ext-searchbox';

  // Keybindings
  import 'ace-builds/src-noconflict/keybinding-vim';
  import 'ace-builds/src-noconflict/keybinding-vscode';

  // Themes
  import 'ace-builds/src-noconflict/theme-one_dark';
  import 'ace-builds/src-noconflict/theme-monokai';
  import 'ace-builds/src-noconflict/theme-dracula';
  import 'ace-builds/src-noconflict/theme-nord_dark';
  import 'ace-builds/src-noconflict/theme-gruvbox';
  import 'ace-builds/src-noconflict/theme-tomorrow_night';

  // Languages & Grammars
  import 'ace-builds/src-noconflict/mode-golang';
  import 'ace-builds/src-noconflict/mode-javascript';
  import 'ace-builds/src-noconflict/mode-typescript';
  import 'ace-builds/src-noconflict/mode-python';
  import 'ace-builds/src-noconflict/mode-rust';
  import 'ace-builds/src-noconflict/mode-c_cpp';
  import 'ace-builds/src-noconflict/mode-java';
  import 'ace-builds/src-noconflict/mode-json';
  import 'ace-builds/src-noconflict/mode-html';
  import 'ace-builds/src-noconflict/mode-css';
  import 'ace-builds/src-noconflict/mode-sh';
  import 'ace-builds/src-noconflict/mode-markdown';
  import 'ace-builds/src-noconflict/mode-yaml';
  import 'ace-builds/src-noconflict/mode-sql';
  import 'ace-builds/src-noconflict/mode-lua';
  import 'ace-builds/src-noconflict/mode-fortran';
  import 'ace-builds/src-noconflict/mode-assembly_x86';
  import 'ace-builds/src-noconflict/mode-dockerfile';

  import type { EditorSettings } from './types';

  let {
    content = '',
    filename = 'main.go',
    vimMode = $bindable('NORMAL'),
    cursorCoords = $bindable('1:1'),
    settings = {
      theme: 'one_dark',
      font: "'Fira Code', monospace",
      fontSize: 13,
      tabSize: 4,
      lineWrapping: false,
      vimEnabled: false,
      keyboardHandler: 'vscode'
    } as EditorSettings,
    onContentChange = (val: string) => {}
  } = $props();

  let editorEl: HTMLDivElement;
  let editor: ace.Ace.Editor;
  let currentFile = '';
  let isInternalChange = false;

  function resolveAceMode(fname: string): string {
    const ext = fname.split('.').pop()?.toLowerCase() || '';
    switch (ext) {
      case 'go': return 'ace/mode/golang';
      case 'js':
      case 'mjs':
      case 'cjs': return 'ace/mode/javascript';
      case 'ts': return 'ace/mode/typescript';
      case 'py': return 'ace/mode/python';
      case 'rs': return 'ace/mode/rust';
      case 'c':
      case 'h':
      case 'cpp':
      case 'cc':
      case 'hpp': return 'ace/mode/c_cpp';
      case 'java': return 'ace/mode/java';
      case 'json': return 'ace/mode/json';
      case 'html': return 'ace/mode/html';
      case 'css': return 'ace/mode/css';
      case 'sh':
      case 'bash': return 'ace/mode/sh';
      case 'md': return 'ace/mode/markdown';
      case 'yaml':
      case 'yml': return 'ace/mode/yaml';
      case 'sql': return 'ace/mode/sql';
      case 'lua': return 'ace/mode/lua';
      case 'f':
      case 'f90': return 'ace/mode/fortran';
      case 's':
      case 'asm': return 'ace/mode/assembly_x86';
      default:
        if (fname.toLowerCase() === 'dockerfile') return 'ace/mode/dockerfile';
        return 'ace/mode/text';
    }
  }

  onMount(() => {
    currentFile = filename;

    // Initialize Ace
    editor = ace.edit(editorEl, {
      value: content,
      mode: resolveAceMode(filename),
      theme: `ace/theme/${settings.theme}`,
      fontSize: `${settings.fontSize}px`,
      fontFamily: settings.font,
      tabSize: settings.tabSize,
      useSoftTabs: true,
      wrap: settings.lineWrapping,
      showPrintMargin: false,
      cursorStyle: 'ace',
      behavioursEnabled: true,
      wrapBehavioursEnabled: true,
      autoScrollEditorIntoView: true,
      enableBasicAutocompletion: true,
      enableLiveAutocompletion: true,
      enableSnippets: true
    });

    // Configure Keyboard Handler (Vim or VSCode)
    if (settings.vimEnabled) {
      editor.setKeyboardHandler('ace/keyboard/vim');
    }

    // Cursor tracking
    editor.selection.on('changeCursor', () => {
      const pos = editor.getCursorPosition();
      cursorCoords = `${pos.row + 1}:${pos.column + 1}`;
    });

    // Text mutation & editing event
    editor.session.on('change', () => {
      if (!isInternalChange) {
        const val = editor.getValue();
        onContentChange(val);
      }
    });

    // Ensure touch focus triggers the soft keyboard reliably
    editorEl.addEventListener('touchstart', () => {
      editor.focus();
    }, { passive: true });

    return () => {
      editor?.destroy();
    };
  });

  // Hot-reload buffer when switching tabs
  $effect(() => {
    if (editor && filename !== currentFile) {
      currentFile = filename;
      isInternalChange = true;
      editor.setValue(content, -1); // -1 moves cursor to start
      editor.session.setMode(resolveAceMode(filename));
      isInternalChange = false;
    }
  });

  // Settings Updates
  $effect(() => {
    if (editor && settings.theme) {
      editor.setTheme(`ace/theme/${settings.theme}`);
    }
  });

  $effect(() => {
    if (editor && settings.fontSize) {
      editor.setFontSize(`${settings.fontSize}px`);
    }
  });

  $effect(() => {
    if (editor && settings.font) {
      editor.setOption('fontFamily', settings.font);
    }
  });

  $effect(() => {
    if (editor) {
      editor.session.setUseWrapMode(settings.lineWrapping);
    }
  });

  $effect(() => {
    if (editor) {
      editor.setKeyboardHandler(settings.vimEnabled ? 'ace/keyboard/vim' : null);
    }
  });

  export function getValue(): string {
    return editor ? editor.getValue() : content;
  }

  export function setValue(newVal: string) {
    if (editor) {
      isInternalChange = true;
      editor.setValue(newVal, -1);
      isInternalChange = false;
    }
  }

  export function focus() {
    editor?.focus();
  }

  export function injectVimKey(key: string) {
    if (!editor) return;
    editor.focus();
    // Pass key input directly to Ace's textarea
    const textarea = editorEl.querySelector('textarea');
    if (textarea) {
      if (key === 'ESC') {
        const evt = new KeyboardEvent('keydown', { key: 'Escape', keyCode: 27, bubbles: true });
        textarea.dispatchEvent(evt);
      } else {
        editor.insert(key);
      }
    }
  }
</script>

<div class="ace-wrapper">
  <div bind:this={editorEl} class="ace-container"></div>
</div>

<style>
  .ace-wrapper {
    display: flex;
    flex: 1;
    width: 100%;
    height: 100%;
    position: relative;
    overflow: hidden;
    background-color: #0d1117;
  }
  .ace-container {
    width: 100% !important;
    height: 100% !important;
    position: absolute !important;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    font-family: inherit !important;
  }
  :global(.ace_editor) {
    font-family: inherit !important;
    line-height: 1.5 !important;
    background-color: #0d1117 !important;
  }
  :global(.ace_gutter) {
    background-color: #0d1117 !important;
    color: #484f58 !important;
    border-right: 1px solid #21262d;
  }
  :global(.ace_cursor) {
    border-left: 2px solid #58a6ff !important;
  }
  :global(.ace_active-line) {
    background-color: rgba(255, 255, 255, 0.04) !important;
  }
</style>
