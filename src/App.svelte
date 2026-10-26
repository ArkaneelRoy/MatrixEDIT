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
  import fuzzysort from 'fuzzysort';
  import { Ssh } from './lib/ssh';
  import { 
    Folder, 
    FolderOpen, 
    FileCode, 
    Terminal as TermIcon, 
    Save, 
    RefreshCw, 
    X, 
    Search, 
    ChevronRight, 
    ChevronDown,
    FilePlus,
    FolderPlus,
    Edit2,
    Trash2,
    PanelLeftClose,
    PanelLeft
  } from 'lucide-svelte';

  // --- Types ---
  interface FileNode {
    name: string;
    path: string;
    isDirectory: boolean;
    isOpen?: boolean;
    children?: FileNode[];
  }

  interface TabItem {
    name: string;
    path: string;
    content: string;
    isDirty: boolean;
  }

  // --- State (Svelte 5 Runes) ---
  let tree = $state<FileNode[]>([]);
  let openTabs = $state<TabItem[]>([]);
  let activeTabPath = $state<string>('');
  let rootPath = $state('/data/data/com.termux/files/home');
  let vimMode = $state('NORMAL');
  let cursorCoords = $state('1:1');
  let isTerminalOpen = $state(true);
  let isSidebarOpen = $state(true);
  let statusMessage = $state('Disconnected');
  let isConnected = $state(false);

  // Modals & Action States
  let isPaletteOpen = $state(false);
  let paletteQuery = $state('');
  let allFlattenedFiles = $state<string[]>([]);
  let showActionModal = $state(false);
  let actionModalType = $state<'newFile' | 'newFolder' | 'rename'>('newFile');
  let actionTargetNode = $state<FileNode | null>(null);
  let actionInputName = $state('');

  // Persistent SSH Settings
  let sshHost = $state(localStorage.getItem('me_ssh_host') || '127.0.0.1');
  let sshPort = $state(Number(localStorage.getItem('me_ssh_port')) || 8022);
  let sshUser = $state(localStorage.getItem('me_ssh_user') || '');
  let sshPass = $state(localStorage.getItem('me_ssh_pass') || '');
  let showConnectDialog = $state(false);

  // DOM Elements
  let editorParent: HTMLDivElement;
  let termParent: HTMLDivElement;
  let editorView: EditorView;
  let term: Terminal;
  let fitAddon: FitAddon;

  onMount(() => {
    // 1. Initialize CodeMirror 6 with Vim Engine
    const state = EditorState.create({
      doc: '// matrixEdit (ME)\n// Connect to Termux OpenSSH to begin editing.\n',
      extensions: [
        lineNumbers(),
        vim({ status: false }),
        oneDark,
        keymap.of(defaultKeymap),
        EditorView.updateListener.of((update) => {
          const cm = getCM(editorView);
          if (cm?.state?.vim) vimMode = cm.state.vim.mode.toUpperCase();
          if (update.selectionSet) {
            const head = update.state.selection.main.head;
            const line = update.state.doc.lineAt(head);
            cursorCoords = `${line.number}:${head - line.from + 1}`;
          }
          if (update.docChanged && activeTabPath) {
            const tab = openTabs.find(t => t.path === activeTabPath);
            if (tab) {
              tab.content = editorView.state.doc.toString();
              tab.isDirty = true;
            }
          }
        })
      ]
    });

    editorView = new EditorView({ state, parent: editorParent });

    // 2. Initialize Terminal
    term = new Terminal({
      theme: {
        background: '#090d13',
        foreground: '#c9d1d9',
        cursor: '#58a6ff',
        selectionBackground: '#264f78'
      },
      fontFamily: "'Fira Code', monospace",
      fontSize: 12,
      lineHeight: 1.2,
      cursorBlink: true,
      convertEol: true
    });

    fitAddon = new FitAddon();
    term.loadAddon(fitAddon);
    term.open(termParent);
    setTimeout(() => fitAddon.fit(), 250);

    term.onData((data) => {
      if (isConnected) Ssh.sendTerminalInput({ data }).catch(() => {});
      else term.write(data);
    });

    try {
      Ssh.addListener('terminalData', ({ chunk }) => term.write(chunk));
    } catch (_) {}

    // Global Key Shortcuts (Ctrl+P, Ctrl+B, Ctrl+S)
    const handleKeyDown = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'p') {
        e.preventDefault();
        isPaletteOpen = !isPaletteOpen;
        paletteQuery = '';
      }
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'b') {
        e.preventDefault();
        isSidebarOpen = !isSidebarOpen;
      }
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
        e.preventDefault();
        saveCurrentTab();
      }
    };
    window.addEventListener('keydown', handleKeyDown);

    return () => {
      window.removeEventListener('keydown', handleKeyDown);
      editorView?.destroy();
      term?.dispose();
    };
  });

  // --- SSH & File System Operations ---
  async function connectSsh() {
    try {
      statusMessage = 'Connecting...';
      localStorage.setItem('me_ssh_host', sshHost);
      localStorage.setItem('me_ssh_port', String(sshPort));
      localStorage.setItem('me_ssh_user', sshUser);
      localStorage.setItem('me_ssh_pass', sshPass);

      await Ssh.connect({ host: sshHost, port: Number(sshPort), username: sshUser, password: sshPass });
      isConnected = true;
      statusMessage = `${sshHost}:${sshPort}`;
      showConnectDialog = false;
      await loadRootDirectory();
    } catch (err: any) {
      statusMessage = 'Failed';
      alert('SSH Connection Failed: ' + (err?.message || err));
    }
  }

  async function loadRootDirectory() {
    tree = await fetchDirectory(rootPath);
    updateFlattenedFiles();
  }

  async function fetchDirectory(path: string): Promise<FileNode[]> {
    const res = await Ssh.readDir({ path });
    return res.files
      .sort((a, b) => (a.isDirectory === b.isDirectory ? a.name.localeCompare(b.name) : a.isDirectory ? -1 : 1))
      .map(f => ({
        name: f.name,
        path: `${path}/${f.name}`,
        isDirectory: f.isDirectory,
        isOpen: false,
        children: f.isDirectory ? [] : undefined
      }));
  }

  async function toggleFolder(node: FileNode) {
    if (!node.isDirectory) return;
    if (node.isOpen) {
      node.isOpen = false;
    } else {
      if (!node.children || node.children.length === 0) {
        node.children = await fetchDirectory(node.path);
        updateFlattenedFiles();
      }
      node.isOpen = true;
    }
  }

  function updateFlattenedFiles() {
    const list: string[] = [];
    const traverse = (nodes: FileNode[]) => {
      for (const n of nodes) {
        if (!n.isDirectory) list.push(n.path);
        if (n.children) traverse(n.children);
      }
    };
    traverse(tree);
    allFlattenedFiles = list;
  }

  // --- File System CRUD Actions ---
  function openCreateModal(type: 'newFile' | 'newFolder', targetNode?: FileNode) {
    actionModalType = type;
    actionTargetNode = targetNode || null;
    actionInputName = '';
    showActionModal = true;
  }

  function openRenameModal(node: FileNode) {
    actionModalType = 'rename';
    actionTargetNode = node;
    actionInputName = node.name;
    showActionModal = true;
  }

  async function executeModalAction() {
    if (!actionInputName.trim()) return;
    const parentDir = actionTargetNode 
      ? (actionTargetNode.isDirectory ? actionTargetNode.path : actionTargetNode.path.substring(0, actionTargetNode.path.lastIndexOf('/')))
      : rootPath;

    try {
      if (actionModalType === 'newFile') {
        const fullPath = `${parentDir}/${actionInputName.trim()}`;
        await Ssh.createFile({ path: fullPath });
        await openFile(fullPath);
      } else if (actionModalType === 'newFolder') {
        const fullPath = `${parentDir}/${actionInputName.trim()}`;
        await Ssh.mkdir({ path: fullPath });
      } else if (actionModalType === 'rename' && actionTargetNode) {
        const oldPath = actionTargetNode.path;
        const newPath = `${oldPath.substring(0, oldPath.lastIndexOf('/'))}/${actionInputName.trim()}`;
        await Ssh.rename({ oldPath, newPath });

        // Update tabs if active
        const tab = openTabs.find(t => t.path === oldPath);
        if (tab) {
          tab.path = newPath;
          tab.name = actionInputName.trim();
          if (activeTabPath === oldPath) activeTabPath = newPath;
        }
      }
      showActionModal = false;
      await loadRootDirectory();
    } catch (err: any) {
      alert('Action error: ' + (err?.message || err));
    }
  }

  async function deleteNode(node: FileNode) {
    const confirmMsg = `Delete ${node.isDirectory ? 'folder' : 'file'} "${node.name}"?`;
    if (!confirm(confirmMsg)) return;

    try {
      await Ssh.delete({ path: node.path, isDirectory: node.isDirectory });

      // Close tab if open
      const tabIndex = openTabs.findIndex(t => t.path === node.path);
      if (tabIndex !== -1) openTabs.splice(tabIndex, 1);
      if (activeTabPath === node.path) {
        activeTabPath = openTabs[0]?.path || '';
        if (openTabs[0]) activateTab(openTabs[0]);
      }

      await loadRootDirectory();
    } catch (err: any) {
      alert('Delete failed: ' + (err?.message || err));
    }
  }

  // --- Tab Management ---
  async function openFile(path: string) {
    let tab = openTabs.find(t => t.path === path);
    if (!tab) {
      const res = await Ssh.readFile({ path });
      tab = {
        name: path.split('/').pop() || 'untitled',
        path,
        content: res.content,
        isDirty: false
      };
      openTabs.push(tab);
    }
    activateTab(tab);
    isPaletteOpen = false;
  }

  function activateTab(tab: TabItem) {
    activeTabPath = tab.path;
    editorView.dispatch({
      changes: { from: 0, to: editorView.state.doc.length, insert: tab.content }
    });
  }

  function closeTab(e: MouseEvent, path: string) {
    e.stopPropagation();
    const index = openTabs.findIndex(t => t.path === path);
    if (index === -1) return;
    openTabs.splice(index, 1);
    if (activeTabPath === path) {
      const nextTab = openTabs[index] || openTabs[index - 1];
      if (nextTab) activateTab(nextTab);
      else {
        activeTabPath = '';
        editorView.dispatch({ changes: { from: 0, to: editorView.state.doc.length, insert: '' } });
      }
    }
  }

  async function saveCurrentTab() {
    const tab = openTabs.find(t => t.path === activeTabPath);
    if (!tab || !isConnected) return;
    await Ssh.writeFile({ path: tab.path, content: editorView.state.doc.toString() });
    tab.isDirty = false;
    statusMessage = `Saved ${tab.name}`;
    setTimeout(() => { statusMessage = `${sshHost}:${sshPort}`; }, 2000);
  }

  function handleVimKey(key: string) {
    const cm = getCM(editorView);
    if (!cm) return;
    if (key === 'ESC') cm.handleKey('Esc');
    else cm.handleKey(key);
  }

  let paletteResults = $derived(
    paletteQuery.trim() === ''
      ? allFlattenedFiles.slice(0, 10)
      : fuzzysort.go(paletteQuery, allFlattenedFiles).map(r => r.target).slice(0, 10)
  );
</script>

<div class="me-app">
  <!-- Top Navigation Bar -->
  <header class="me-navbar">
    <div class="nav-brand">
      <button 
        onclick={() => isSidebarOpen = !isSidebarOpen} 
        class="icon-btn-dock" 
        title="Toggle Dock (Ctrl+B)"
      >
        {#if isSidebarOpen}<PanelLeftClose size={15} />{:else}<PanelLeft size={15} />{/if}
      </button>
      <span class="brand-badge">ME</span>
      <span class="brand-name">matrixEdit</span>
      <span class="nav-sep">│</span>
      <span class="nav-breadcrumb">{activeTabPath ? activeTabPath.replace(rootPath, '~') : 'No open file'}</span>
    </div>

    <div class="nav-actions">
      <button onclick={() => isPaletteOpen = true} class="btn-tool" title="Quick Open (Ctrl+P)">
        <Search size={13} />
        <span class="shortcut">Ctrl+P</span>
      </button>
      {#if !isConnected}
        <button onclick={() => showConnectDialog = true} class="btn-connect">Connect SSH</button>
      {:else}
        <button onclick={saveCurrentTab} class="icon-btn" title="Save (:w)"><Save size={14} /></button>
        <button onclick={loadRootDirectory} class="icon-btn" title="Refresh Tree"><RefreshCw size={14} /></button>
      {/if}
      <button 
        onclick={() => {
          isTerminalOpen = !isTerminalOpen;
          if (isTerminalOpen) setTimeout(() => fitAddon?.fit(), 150);
        }} 
        class="icon-btn" 
        title="Toggle Integrated Shell"
      >
        <TermIcon size={14} />
      </button>
    </div>
  </header>

  <!-- VS Code-Style Multi-Tab Bar -->
  <div class="me-tabbar">
    {#each openTabs as tab}
      <button 
        onclick={() => activateTab(tab)} 
        class="tab-pill" 
        class:active-tab={tab.path === activeTabPath}
      >
        <FileCode size={12} class="tab-icon" />
        <span class="tab-title">{tab.name}</span>
        {#if tab.isDirty}
          <span class="dirty-dot">●</span>
        {/if}
        <span onclick={(e) => closeTab(e, tab.path)} class="tab-close"><X size={11} /></span>
      </button>
    {/each}
  </div>

  <!-- Workspace Layout: Collapsible Sidebar + Editor Stage -->
  <div class="me-workspace">
    <!-- Collapsible File Explorer Sidebar Dock -->
    {#if isSidebarOpen}
      <aside class="me-sidebar">
        <div class="sidebar-header">
          <span class="sidebar-label">Explorer</span>
          <div class="sidebar-actions">
            <button onclick={() => openCreateModal('newFile')} class="tree-tool" title="New File"><FilePlus size={13} /></button>
            <button onclick={() => openCreateModal('newFolder')} class="tree-tool" title="New Folder"><FolderPlus size={13} /></button>
            <button onclick={loadRootDirectory} class="tree-tool" title="Refresh"><RefreshCw size={12} /></button>
          </div>
        </div>

        <div class="sidebar-tree">
          {#snippet renderTree(nodes: FileNode[], depth: number)}
            {#each nodes as node}
              <div 
                onclick={() => node.isDirectory ? toggleFolder(node) : openFile(node.path)}
                class="tree-node group"
                style="padding-left: {depth * 14 + 6}px"
              >
                {#if node.isDirectory}
                  <span class="chevron">
                    {#if node.isOpen}<ChevronDown size={12} />{:else}<ChevronRight size={12} />{/if}
                  </span>
                  {#if node.isOpen}
                    <FolderOpen size={13} class="icon-dir-open" />
                  {:else}
                    <Folder size={13} class="icon-dir" />
                  {/if}
                {:else}
                  <span class="chevron-spacer"></span>
                  <FileCode size={13} class="icon-file" />
                {/if}
                <span class="node-name">{node.name}</span>

                <!-- Hover Context Actions (Rename / Delete) -->
                <div class="node-actions" onclick={(e) => e.stopPropagation()}>
                  <button onclick={() => openRenameModal(node)} title="Rename" class="node-btn"><Edit2 size={11} /></button>
                  <button onclick={() => deleteNode(node)} title="Delete" class="node-btn text-red-400"><Trash2 size={11} /></button>
                </div>
              </div>
              {#if node.isDirectory && node.isOpen && node.children}
                {@render renderTree(node.children, depth + 1)}
              {/if}
            {/each}
          {/snippet}

          {#if tree.length > 0}
            {@render renderTree(tree, 0)}
          {:else}
            <div class="empty-tree">Connect to inspect files</div>
          {/if}
        </div>
      </aside>
    {/if}

    <!-- Editor Stage -->
    <main class="me-stage">
      <div bind:this={editorParent} class="editor-viewport"></div>

      <!-- Quick Action Mobile Key Strip -->
      <div class="mobile-keys">
        {#each ['ESC', ':', '/', 'i', 'w', 'b', '$', '0', '{', '}', ';'] as k}
          <button onclick={() => handleVimKey(k)} class="key-item">{k}</button>
        {/each}
      </div>

      <!-- Integrated Left-Aligned Terminal Drawer -->
      {#if isTerminalOpen}
        <div class="terminal-drawer">
          <div class="terminal-bar">
            <span>TERMINAL (PTY {sshPort})</span>
            <button onclick={() => isTerminalOpen = false} class="close-term"><X size={12} /></button>
          </div>
          <div bind:this={termParent} class="terminal-mount"></div>
        </div>
      {/if}
    </main>

    <!-- Create/Rename Item Modal -->
    {#if showActionModal}
      <div class="dialog-backdrop">
        <div class="dialog-card">
          <div class="dialog-title">
            {#if actionModalType === 'newFile'}Create New File{/if}
            {#if actionModalType === 'newFolder'}Create New Folder{/if}
            {#if actionModalType === 'rename'}Rename Item{/if}
          </div>
          <div class="input-field">
            <label>Name</label>
            <input 
              bind:value={actionInputName} 
              onkeydown={(e) => e.key === 'Enter' && executeModalAction()} 
              autofocus 
              placeholder="e.g. index.go" 
            />
          </div>
          <div class="dialog-buttons">
            <button onclick={() => showActionModal = false} class="btn-cancel">Cancel</button>
            <button onclick={executeModalAction} class="btn-confirm">Submit</button>
          </div>
        </div>
      </div>
    {/if}

    <!-- Command Palette / Quick Open Modal -->
    {#if isPaletteOpen}
      <div onclick={() => isPaletteOpen = false} class="palette-backdrop">
        <div onclick={(e) => e.stopPropagation()} class="palette-window">
          <div class="palette-input-box">
            <Search size={14} class="palette-search-icon" />
            <input 
              bind:value={paletteQuery} 
              placeholder="Search files by name (Ctrl+P)..." 
              autofocus 
              class="palette-input" 
            />
          </div>
          <div class="palette-results">
            {#each paletteResults as item}
              <button onclick={() => openFile(item)} class="palette-item">
                <FileCode size={13} class="icon-file" />
                <span class="palette-filename">{item.split('/').pop()}</span>
                <span class="palette-filepath">{item.replace(rootPath, '~')}</span>
              </button>
            {/each}
          </div>
        </div>
      </div>
    {/if}

    <!-- SSH Connection Dialog -->
    {#if showConnectDialog}
      <div class="dialog-backdrop">
        <div class="dialog-card">
          <div class="dialog-title">Connect to Termux OpenSSH</div>
          <div class="input-field">
            <label>Host & Port</label>
            <div class="row">
              <input bind:value={sshHost} placeholder="127.0.0.1" />
              <input bind:value={sshPort} type="number" style="width: 75px;" placeholder="8022" />
            </div>
          </div>
          <div class="input-field">
            <label>Username (run `whoami` in Termux)</label>
            <input bind:value={sshUser} placeholder="u0_a..." />
          </div>
          <div class="input-field">
            <label>Password (from `passwd` in Termux)</label>
            <input bind:value={sshPass} type="password" placeholder="••••••••" />
          </div>
          <div class="input-field">
            <label>Working Directory</label>
            <input bind:value={rootPath} />
          </div>
          <div class="dialog-buttons">
            <button onclick={() => showConnectDialog = false} class="btn-cancel">Cancel</button>
            <button onclick={connectSsh} class="btn-confirm">Connect</button>
          </div>
        </div>
      </div>
    {/if}
  </div>

  <!-- Statusline -->
   <!-- Statusline -->
  <footer class="me-statusline">
    <div class="status-sec-left">
      <span class="mode-tag" class:insert={vimMode === 'INSERT'}>{vimMode}</span>
      <span class="status-info">{statusMessage}</span>
    </div>
    <div class="status-sec-right">
      <span>UTF-8</span>
      <span>{activeTabPath.split('.').pop() || 'plain'}</span>
      <span>Ln {cursorCoords.split(':')[0]}, Col {cursorCoords.split(':')[1]}</span>
    </div>
  </footer>
</div>

<style>
  .me-app {
    display: flex;
    flex-direction: column;
    width: 100vw;
    height: 100vh;
    background-color: #0d1117;
    color: #c9d1d9;
    font-family: 'Fira Code', monospace;
    overflow: hidden;
    text-align: left;
  }

  .me-navbar {
    display: flex;
    justify-content: space-between;
    align-items: center;
    height: 35px;
    background-color: #161b22;
    border-bottom: 1px solid #30363d;
    padding: 0 10px;
  }

  .nav-brand { display: flex; align-items: center; gap: 8px; font-size: 12px; }
  .icon-btn-dock { background: transparent; border: none; color: #8b949e; cursor: pointer; display: flex; padding: 2px; }
  .icon-btn-dock:hover { color: #58a6ff; }
  .brand-badge { background-color: #238636; color: #fff; font-weight: 700; padding: 1px 5px; border-radius: 3px; font-size: 10px; }
  .brand-name { font-weight: 600; color: #58a6ff; }
  .nav-sep { color: #30363d; }
  .nav-breadcrumb { color: #8b949e; font-size: 11px; }

  .nav-actions { display: flex; align-items: center; gap: 6px; }
  .btn-tool {
    display: flex;
    align-items: center;
    gap: 6px;
    background-color: #21262d;
    border: 1px solid #30363d;
    color: #8b949e;
    font-size: 11px;
    padding: 3px 8px;
    border-radius: 4px;
    cursor: pointer;
    font-family: inherit;
  }
  .shortcut { font-size: 9px; background-color: #30363d; color: #c9d1d9; padding: 1px 4px; border-radius: 2px; }
  .btn-connect {
    background-color: #238636;
    color: #fff;
    border: none;
    font-weight: 600;
    font-size: 11px;
    padding: 4px 8px;
    border-radius: 4px;
    cursor: pointer;
  }
  .icon-btn { background: transparent; border: none; color: #8b949e; padding: 4px; cursor: pointer; display: flex; }
  .icon-btn:hover { color: #c9d1d9; }

  /* Tabs Bar */
  .me-tabbar {
    display: flex;
    height: 29px;
    background-color: #0d1117;
    border-bottom: 1px solid #21262d;
    overflow-x: auto;
  }
  .tab-pill {
    display: flex;
    align-items: center;
    gap: 6px;
    height: 100%;
    background-color: #161b22;
    border: none;
    border-right: 1px solid #21262d;
    color: #8b949e;
    padding: 0 10px;
    font-family: inherit;
    font-size: 11px;
    cursor: pointer;
    white-space: nowrap;
  }
  .tab-pill.active-tab { background-color: #0d1117; color: #58a6ff; border-top: 2px solid #58a6ff; }
  .dirty-dot { color: #d29922; font-size: 10px; }
  .tab-close { margin-left: 4px; border-radius: 2px; padding: 1px; }
  .tab-close:hover { background-color: #30363d; color: #fff; }

  /* Workspace Layout */
  .me-workspace { display: flex; flex: 1; overflow: hidden; position: relative; }
  .me-sidebar {
    width: 220px;
    background-color: #0d1117;
    border-right: 1px solid #21262d;
    display: flex;
    flex-direction: column;
    flex-shrink: 0;
  }
  .sidebar-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 6px 10px;
    font-size: 10px;
    font-weight: 700;
    text-transform: uppercase;
    color: #8b949e;
    border-bottom: 1px solid #21262d;
  }
  .sidebar-actions { display: flex; gap: 4px; }
  .tree-tool { background: transparent; border: none; color: #8b949e; cursor: pointer; padding: 1px 3px; border-radius: 2px; }
  .tree-tool:hover { color: #58a6ff; background-color: #21262d; }

  .sidebar-tree { flex: 1; overflow-y: auto; padding: 4px 0; }
  
  .tree-node {
    display: flex;
    align-items: center;
    gap: 4px;
    height: 25px;
    cursor: pointer;
    font-size: 11px;
    color: #c9d1d9;
    text-align: left;
    padding-right: 6px;
    position: relative;
  }
  .tree-node:hover { background-color: #161b22; }
  .chevron { width: 14px; display: flex; color: #8b949e; }
  .chevron-spacer { width: 14px; }
  .icon-dir { color: #58a6ff; }
  .icon-dir-open { color: #79c0ff; }
  .icon-file { color: #8b949e; }
  .node-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; }

  .node-actions { display: none; gap: 4px; margin-left: auto; }
  .tree-node:hover .node-actions { display: flex; }
  .node-btn { background: transparent; border: none; color: #8b949e; cursor: pointer; padding: 2px; border-radius: 2px; }
  .node-btn:hover { background-color: #30363d; color: #fff; }

  .empty-tree { padding: 12px; font-size: 11px; color: #484f58; text-align: center; }

  /* Stage & Code Viewport */
  .me-stage { display: flex; flex-direction: column; flex: 1; min-width: 0; background-color: #0d1117; }
  .editor-viewport { flex: 1; height: 100%; overflow: hidden; text-align: left !important; }

  /* Mobile Keys */
  .mobile-keys {
    display: flex;
    gap: 4px;
    padding: 3px 6px;
    background-color: #161b22;
    border-top: 1px solid #21262d;
    overflow-x: auto;
  }
  .key-item {
    background-color: #21262d;
    border: 1px solid #30363d;
    color: #c9d1d9;
    padding: 2px 8px;
    border-radius: 3px;
    font-family: inherit;
    font-weight: 600;
    font-size: 11px;
  }

  /* Terminal Dock */
  .terminal-drawer { height: 160px; border-top: 1px solid #30363d; background-color: #090d13; display: flex; flex-direction: column; }
  .terminal-bar {
    display: flex;
    justify-content: space-between;
    padding: 2px 8px;
    background-color: #161b22;
    font-size: 10px;
    color: #8b949e;
    border-bottom: 1px solid #21262d;
  }
  .close-term { background: transparent; border: none; color: #8b949e; cursor: pointer; }
  .terminal-mount { flex: 1; overflow: hidden; text-align: left !important; }

  /* Status Bar */
  .me-statusline {
    display: flex;
    justify-content: space-between;
    align-items: center;
    height: 22px;
    background-color: #161b22;
    border-top: 1px solid #21262d;
    padding: 0 8px;
    font-size: 11px;
  }
  .status-sec-left, .status-sec-right { display: flex; align-items: center; gap: 8px; }
  .mode-tag { background-color: #1f6feb; color: #fff; font-weight: 700; padding: 0 5px; border-radius: 2px; }
  .mode-tag.insert { background-color: #238636; }
  .status-info { color: #8b949e; }

  /* Modals */
  .dialog-backdrop, .palette-backdrop {
    position: absolute;
    inset: 0;
    background: rgba(0,0,0,0.65);
    display: flex;
    justify-content: center;
    align-items: center;
    z-index: 999;
  }
  .palette-backdrop { padding-top: 40px; align-items: flex-start; }
  .dialog-card, .palette-window {
    background-color: #161b22;
    border: 1px solid #30363d;
    border-radius: 6px;
    width: 310px;
    padding: 14px;
    display: flex;
    flex-direction: column;
    gap: 10px;
  }
  .palette-window { width: 90%; max-width: 480px; padding: 0; }
  .palette-input-box { display: flex; align-items: center; padding: 8px 12px; border-bottom: 1px solid #30363d; gap: 8px; }
  .palette-search-icon { color: #58a6ff; }
  .palette-input { width: 100%; background: transparent; border: none; color: #fff; font-family: inherit; font-size: 12px; outline: none; }
  .palette-results { max-height: 220px; overflow-y: auto; }
  .palette-item {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 100%;
    padding: 6px 12px;
    background: transparent;
    border: none;
    cursor: pointer;
    font-family: inherit;
    text-align: left;
  }
  .palette-item:hover { background-color: #21262d; }
  .palette-filename { font-size: 12px; color: #c9d1d9; }
  .palette-filepath { font-size: 10px; color: #8b949e; margin-left: auto; }

  .dialog-title { font-weight: 700; color: #58a6ff; font-size: 13px; border-bottom: 1px solid #30363d; padding-bottom: 4px; }
  .input-field label { display: block; font-size: 10px; color: #8b949e; margin-bottom: 2px; }
  .input-field input { width: 100%; background-color: #0d1117; border: 1px solid #30363d; color: #fff; padding: 4px 6px; font-family: inherit; font-size: 11px; border-radius: 3px; outline: none; }
  .row { display: flex; gap: 6px; }
  .dialog-buttons { display: flex; justify-content: flex-end; gap: 6px; margin-top: 4px; }
  .btn-confirm { background-color: #238636; color: #fff; border: none; font-weight: 600; padding: 4px 10px; border-radius: 3px; cursor: pointer; }
  .btn-cancel { background-color: #21262d; color: #c9d1d9; border: 1px solid #30363d; padding: 4px 10px; border-radius: 3px; cursor: pointer; }
</style>