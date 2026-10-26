<script lang="ts">
  import { onMount } from 'svelte';
  import { EditorState } from '@codemirror/state';
  import { EditorView, keymap, lineNumbers } from '@codemirror/view';
  import { defaultKeymap } from '@codemirror/commands';
  import { vim, getCM } from '@replit/codemirror-vim';
  import { oneDark } from '@codemirror/theme-one-dark';
  import { Terminal } from '@xterm/xterm';
  import { FitAddon } from '@xterm/addon-fit';
  import '@xterm/xterm/css/xterm.css';
  import { Ssh } from './lib/ssh';
  import { 
    Folder, 
    FileCode, 
    Terminal as TermIcon, 
    Save, 
    RefreshCw, 
    X,
    FolderGit2
  } from 'lucide-svelte';

  // --- Svelte 5 Runes State ---
  let currentFile = $state('main.go');
  let currentPath = $state('/data/data/com.termux/files/home');
  let fileList = $state<Array<{ name: string; isDirectory: boolean }>>([
    { name: 'main.go', isDirectory: false },
    { name: 'config.json', isDirectory: false },
    { name: 'src', isDirectory: true }
  ]);
  let vimMode = $state('NORMAL');
  let cursorCoords = $state('1:1');
  let isTerminalOpen = $state(true);
  let statusMessage = $state('127.0.0.1:8022 (Termux)');
  let isConnected = $state(false);

  // SSH Connection Dialog
  let sshHost = $state('127.0.0.1');
  let sshPort = $state(8022);
  let sshUser = $state('');
  let sshPass = $state('');
  let showConnectDialog = $state(false);

  let editorParent: HTMLDivElement;
  let termParent: HTMLDivElement;
  let editorView: EditorView;
  let term: Terminal;
  let fitAddon: FitAddon;

  onMount(() => {
    // 1. Initialize CodeMirror 6 with Vim Mode
    const state = EditorState.create({
      doc: '// MatrixEdit: Hybrid Neovim + Brackets layout over SSH\npackage main\n\nimport "fmt"\n\nfunc main() {\n\tfmt.Println("Connected to Termux over loopback SSH!")\n}',
      extensions: [
        lineNumbers(),
        vim({ status: false }),
        oneDark,
        keymap.of(defaultKeymap),
        EditorView.updateListener.of((update) => {
          const cm = getCM(editorView);
          if (cm?.state?.vim) {
            vimMode = cm.state.vim.mode.toUpperCase();
          }
          if (update.selectionSet) {
            const head = update.state.selection.main.head;
            const line = update.state.doc.lineAt(head);
            cursorCoords = `${line.number}:${head - line.from + 1}`;
          }
        })
      ]
    });

    editorView = new EditorView({ state, parent: editorParent });

    // 2. Initialize xterm.js
    term = new Terminal({
      theme: {
        background: '#11111b',
        foreground: '#cdd6f4',
        cursor: '#f5e0dc',
        selectionBackground: '#45475a'
      },
      fontFamily: 'monospace',
      fontSize: 12,
      cursorBlink: true
    });

    fitAddon = new FitAddon();
    term.loadAddon(fitAddon);
    term.open(termParent);
    setTimeout(() => fitAddon.fit(), 200);

    term.onData((data) => {
      if (isConnected) {
        Ssh.sendTerminalInput({ data }).catch(() => {});
      } else {
        term.write(data); // Local echo if disconnected
      }
    });

    try {
      Ssh.addListener('terminalData', ({ chunk }) => {
        term.write(chunk);
      });
    } catch (_) {}

    window.addEventListener('resize', () => {
      if (isTerminalOpen && fitAddon) fitAddon.fit();
    });

    return () => {
      editorView?.destroy();
      term?.dispose();
    };
  });

  async function connectSsh() {
    try {
      statusMessage = 'Connecting...';
      await Ssh.connect({
        host: sshHost,
        port: Number(sshPort),
        username: sshUser,
        password: sshPass
      });
      isConnected = true;
      statusMessage = `${sshHost}:${sshPort}`;
      showConnectDialog = false;
      await refreshFiles();
    } catch (err: any) {
      statusMessage = 'Connection failed';
      alert('SSH Connection Failed: ' + (err?.message || err));
    }
  }

  async function refreshFiles() {
    if (!isConnected) return;
    try {
      const res = await Ssh.readDir({ path: currentPath });
      fileList = res.files.sort((a, b) => {
        if (a.isDirectory === b.isDirectory) return a.name.localeCompare(b.name);
        return a.isDirectory ? -1 : 1;
      });
    } catch (err: any) {
      alert('Read directory error: ' + (err?.message || err));
    }
  }

  async function openFile(filename: string) {
    if (!isConnected) return;
    const fullPath = `${currentPath}/${filename}`;
    try {
      const res = await Ssh.readFile({ path: fullPath });
      currentFile = filename;
      editorView.dispatch({
        changes: { from: 0, to: editorView.state.doc.length, insert: res.content }
      });
    } catch (err: any) {
      alert('Failed to read file: ' + (err?.message || err));
    }
  }

  async function saveCurrentFile() {
    if (!isConnected) return;
    const fullPath = `${currentPath}/${currentFile}`;
    const content = editorView.state.doc.toString();
    try {
      await Ssh.writeFile({ path: fullPath, content });
      statusMessage = `Saved ${currentFile}`;
      setTimeout(() => { statusMessage = `${sshHost}:${sshPort}`; }, 2000);
    } catch (err: any) {
      alert('Failed to save file: ' + (err?.message || err));
    }
  }

  function handleVimSoftKey(key: string) {
    const cm = getCM(editorView);
    if (!cm) return;
    if (key === 'ESC') cm.handleKey('Esc');
    else cm.handleKey(key);
  }
</script>

<div class="matrix-root">
  <!-- 1. Header Toolbar (Brackets style) -->
  <header class="matrix-header">
    <div class="header-left">
      <span class="logo">NV:BRACKETS</span>
      <span class="divider">│</span>
      <span class="active-file">{currentFile}*</span>
    </div>

    <div class="header-right">
      {#if !isConnected}
        <button onclick={() => showConnectDialog = true} class="btn-primary">
          Connect SSH
        </button>
      {:else}
        <button onclick={saveCurrentFile} class="icon-btn" title="Save (:w)"><Save size={14} /></button>
        <button onclick={refreshFiles} class="icon-btn" title="Refresh"><RefreshCw size={14} /></button>
      {/if}
      <button 
        onclick={() => {
          isTerminalOpen = !isTerminalOpen;
          if (isTerminalOpen) setTimeout(() => fitAddon?.fit(), 100);
        }} 
        class="icon-btn" 
        title="Toggle Terminal"
      >
        <TermIcon size={14} />
      </button>
    </div>
  </header>

  <!-- 2. Workspace Layout: Sidebar + Editor + Terminal -->
  <div class="matrix-workspace">
    <!-- Left Sidebar: File Tree -->
    <aside class="matrix-sidebar">
      <div class="sidebar-title">
        <span>Files</span>
        <span class="sidebar-path">~/{currentPath.split('/').pop()}</span>
      </div>
      <div class="sidebar-file-list">
        {#each fileList as item}
          <button 
            onclick={() => !item.isDirectory && openFile(item.name)} 
            class="sidebar-item"
          >
            {#if item.isDirectory}
              <Folder size={13} class="icon-dir" />
            {:else}
              <FileCode size={13} class="icon-file" />
            {/if}
            <span class="filename">{item.name}</span>
          </button>
        {/each}
      </div>
    </aside>

    <!-- Center Stage: Editor Buffer & Terminal Drawer -->
    <main class="matrix-editor-area">
      <!-- CodeMirror Mount Element -->
      <div bind:this={editorParent} class="codemirror-mount"></div>

      <!-- Quick Action Bar (Mobile/Touch Virtual Keys) -->
      <div class="quick-keys-bar">
        {#each ['ESC', ':', '/', 'i', 'w', 'b', '$', '0', '{', '}', ';'] as k}
          <button onclick={() => handleVimSoftKey(k)} class="key-pill">{k}</button>
        {/each}
      </div>

      <!-- Bottom Terminal Drawer -->
      {#if isTerminalOpen}
        <div class="matrix-terminal-drawer">
          <div class="terminal-header">
            <span>SHELL (PTY 8022)</span>
            <button onclick={() => isTerminalOpen = false} class="close-term-btn"><X size={12} /></button>
          </div>
          <div bind:this={termParent} class="xterm-mount"></div>
        </div>
      {/if}
    </main>

    <!-- SSH Connection Modal -->
    {#if showConnectDialog}
      <div class="modal-backdrop">
        <div class="modal-card">
          <div class="modal-title">Connect to Termux OpenSSH</div>
          <div class="form-group">
            <label>Host & Port</label>
            <div class="input-row">
              <input bind:value={sshHost} placeholder="127.0.0.1" />
              <input bind:value={sshPort} type="number" style="width: 70px;" placeholder="8022" />
            </div>
          </div>
          <div class="form-group">
            <label>Username (run `whoami` in Termux)</label>
            <input bind:value={sshUser} placeholder="u0_a..." />
          </div>
          <div class="form-group">
            <label>Password (from `passwd` in Termux)</label>
            <input bind:value={sshPass} type="password" placeholder="••••••••" />
          </div>
          <div class="form-group">
            <label>Working Directory</label>
            <input bind:value={currentPath} />
          </div>
          <div class="modal-actions">
            <button onclick={() => showConnectDialog = false} class="btn-secondary">Cancel</button>
            <button onclick={connectSsh} class="btn-primary">Connect</button>
          </div>
        </div>
      </div>
    {/if}
  </div>

  <!-- 3. Neovim Lualine-Style Statusline -->
  <footer class="matrix-statusline">
    <div class="status-left">
      <span class="mode-badge" class:insert-mode={vimMode === 'INSERT'}>{vimMode}</span>
      <span class="status-meta">{statusMessage}</span>
    </div>
    <div class="status-right">
      <span>utf-8</span>
      <span>{currentFile.split('.').pop() || 'text'}</span>
      <span> {cursorCoords}</span>
    </div>
  </footer>
</div>

<style>
  /* Explicit layout rules - completely independent of Tailwind compiler */
  .matrix-root {
    display: flex;
    flex-direction: column;
    width: 100vw;
    height: 100vh;
    background-color: #11111b;
    color: #cdd6f4;
    overflow: hidden;
    font-family: ui-monospace, Menlo, Monaco, Consolas, monospace;
    font-size: 12px;
  }

  .matrix-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    background-color: #181825;
    border-bottom: 1px solid #313244;
    padding: 6px 12px;
    height: 34px;
    box-sizing: border-box;
  }

  .header-left, .header-right {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .logo {
    color: #89b4fa;
    font-weight: 700;
    letter-spacing: 0.5px;
  }

  .divider { color: #45475a; }
  .active-file { color: #fab387; }

  .matrix-workspace {
    display: flex;
    flex: 1;
    overflow: hidden;
    position: relative;
  }

  .matrix-sidebar {
    width: 180px;
    background-color: #181825;
    border-right: 1px solid #313244;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }

  .sidebar-title {
    display: flex;
    justify-content: space-between;
    padding: 6px 10px;
    text-transform: uppercase;
    font-size: 10px;
    font-weight: bold;
    color: #6c7086;
    border-bottom: 1px solid #313244;
  }

  .sidebar-path { text-transform: lowercase; color: #45475a; }

  .sidebar-file-list {
    flex: 1;
    overflow-y: auto;
    padding: 4px;
  }

  .sidebar-item {
    display: flex;
    align-items: center;
    gap: 6px;
    width: 100%;
    padding: 4px 6px;
    background: transparent;
    border: none;
    color: #a6adc8;
    cursor: pointer;
    border-radius: 4px;
    text-align: left;
    font-family: inherit;
    font-size: 11px;
  }

  .sidebar-item:hover { background-color: #313244; color: #fff; }
  .icon-dir { color: #89b4fa; }
  .icon-file { color: #a6adc8; }
  .filename { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

  .matrix-editor-area {
    display: flex;
    flex-direction: column;
    flex: 1;
    min-width: 0;
    background-color: #1e1e2e;
    overflow: hidden;
  }

  .codemirror-mount {
    flex: 1;
    overflow: hidden;
    height: 100%;
  }

  :global(.cm-editor) {
    height: 100%;
    outline: none !important;
  }

  .quick-keys-bar {
    display: flex;
    gap: 4px;
    padding: 4px 8px;
    background-color: #181825;
    border-top: 1px solid #313244;
    overflow-x: auto;
  }

  .key-pill {
    background-color: #313244;
    border: none;
    color: #cdd6f4;
    padding: 3px 8px;
    border-radius: 3px;
    font-family: inherit;
    font-weight: bold;
    cursor: pointer;
  }

  .key-pill:active { background-color: #585b70; }

  .matrix-terminal-drawer {
    height: 160px;
    border-top: 1px solid #313244;
    background-color: #11111b;
    display: flex;
    flex-direction: column;
  }

  .terminal-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 2px 8px;
    background-color: #181825;
    font-size: 10px;
    color: #6c7086;
    border-bottom: 1px solid #313244;
  }

  .close-term-btn {
    background: transparent;
    border: none;
    color: #6c7086;
    cursor: pointer;
  }

  .xterm-mount {
    flex: 1;
    padding: 4px;
    overflow: hidden;
  }

  .matrix-statusline {
    display: flex;
    justify-content: space-between;
    align-items: center;
    background-color: #11111b;
    border-top: 1px solid #313244;
    padding: 2px 8px;
    height: 22px;
    font-size: 11px;
    box-sizing: border-box;
  }

  .status-left, .status-right {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .mode-badge {
    background-color: #89b4fa;
    color: #11111b;
    padding: 1px 6px;
    font-weight: bold;
    border-radius: 2px;
  }

  .mode-badge.insert-mode {
    background-color: #a6e3a1;
  }

  .status-meta { color: #6c7086; }

  .btn-primary {
    background-color: #89b4fa;
    color: #11111b;
    border: none;
    font-weight: bold;
    padding: 3px 8px;
    border-radius: 3px;
    cursor: pointer;
  }

  .btn-secondary {
    background-color: #313244;
    color: #cdd6f4;
    border: none;
    padding: 3px 8px;
    border-radius: 3px;
    cursor: pointer;
  }

  .icon-btn {
    background: transparent;
    border: none;
    color: #a6adc8;
    padding: 3px;
    cursor: pointer;
    display: flex;
  }

  .modal-backdrop {
    position: absolute;
    inset: 0;
    background: rgba(0, 0, 0, 0.65);
    display: flex;
    justify-content: center;
    align-items: center;
    z-index: 100;
  }

  .modal-card {
    background: #181825;
    border: 1px solid #313244;
    padding: 14px;
    border-radius: 6px;
    width: 290px;
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  .modal-title { font-weight: bold; color: #89b4fa; border-bottom: 1px solid #313244; padding-bottom: 4px; }
  .form-group label { display: block; font-size: 10px; color: #a6adc8; margin-bottom: 2px; }
  .form-group input { width: 100%; box-sizing: border-box; background: #11111b; border: 1px solid #313244; color: #fff; padding: 4px 6px; border-radius: 3px; outline: none; }
  .input-row { display: flex; gap: 6px; }
  .modal-actions { display: flex; justify-content: flex-end; gap: 6px; margin-top: 4px; }
</style>
