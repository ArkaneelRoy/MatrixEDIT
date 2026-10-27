<script lang="ts">
  import { onMount } from 'svelte';
  import fuzzysort from 'fuzzysort';
  import { Ssh } from './lib/ssh';
  import Editor from './lib/Editor.svelte';
  import Terminal from './lib/Terminal.svelte';
  import type { FileNode, TabItem, EditorSettings, AceTheme, EditorFont } from './lib/types';

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
    PanelLeft, 
    Settings as SettingsIcon,
    Columns2,
    Rows2,
    LogOut
  } from 'lucide-svelte';

  // --- State ---
  let tree = $state<FileNode[]>([]);
  let openTabs = $state<TabItem[]>([]);
  let rootPath = $state('/data/data/com.termux/files/home');
  let isTerminalOpen = $state(true);
  let isSidebarOpen = $state(true);
  let statusMessage = $state('Disconnected');
  let isConnected = $state(false);

  // Split Screen State
  let isEditorSplit = $state(false);
  let splitDirection = $state<'vertical' | 'horizontal'>('vertical');
  let activePaneIndex = $state<0 | 1>(0);
  let splitRatio = $state(50);

  // Pane 0
  let pane0Path = $state<string>('');
  let pane0Content = $state<string>('// MatrixEdit (ME)\n// Connect SSH to open and edit files.\n');
  let pane0VimMode = $state('NORMAL');
  let pane0Cursor = $state('1:1');

  // Pane 1
  let pane1Path = $state<string>('');
  let pane1Content = $state<string>('');
  let pane1VimMode = $state('NORMAL');
  let pane1Cursor = $state('1:1');

  // Settings
  let showSettingsModal = $state(false);
  let settings = $state<EditorSettings>({
    theme: (localStorage.getItem('me_theme') as AceTheme) || 'one_dark',
    font: (localStorage.getItem('me_font') as EditorFont) || "'Fira Code', monospace",
    fontSize: Number(localStorage.getItem('me_fontSize')) || 13,
    tabSize: 4,
    lineWrapping: localStorage.getItem('me_lineWrap') === 'true',
    vimEnabled: localStorage.getItem('me_vim') === 'true',
    keyboardHandler: 'vscode'
  });

  // Modal / Quick Open
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

  let editorRef0: any;
  let editorRef1: any;
  let stageContainerEl: HTMLDivElement;

  function updateSetting<K extends keyof EditorSettings>(key: K, value: EditorSettings[K]) {
    settings[key] = value;
    if (key === 'theme') localStorage.setItem('me_theme', String(value));
    if (key === 'font') localStorage.setItem('me_font', String(value));
    if (key === 'fontSize') localStorage.setItem('me_fontSize', String(value));
    if (key === 'lineWrapping') localStorage.setItem('me_lineWrap', String(value));
    if (key === 'vimEnabled') localStorage.setItem('me_vim', String(value));
  }

  onMount(() => {
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
      if ((e.ctrlKey || e.metaKey) && e.key === ',') {
        e.preventDefault();
        showSettingsModal = !showSettingsModal;
      }
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
        e.preventDefault();
        saveActivePane();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  });

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

  async function disconnectSsh() {
    try {
      await Ssh.disconnect();
    } catch (_) {
    } finally {
      isConnected = false;
      statusMessage = 'Disconnected';
      tree = [];
      allFlattenedFiles = [];
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

  async function openFile(path: string) {
    let tab = openTabs.find(t => t.path === path);
    if (!tab) {
      const res = await Ssh.readFile({ path });
      tab = { name: path.split('/').pop() || 'untitled', path, content: res.content, isDirty: false };
      openTabs.push(tab);
    }

    if (activePaneIndex === 0) {
      pane0Path = tab.path;
      pane0Content = tab.content;
      editorRef0?.setValue(tab.content);
    } else {
      pane1Path = tab.path;
      pane1Content = tab.content;
      editorRef1?.setValue(tab.content);
    }
    isPaletteOpen = false;
  }

  function toggleEditorSplit(dir: 'vertical' | 'horizontal') {
    if (isEditorSplit && splitDirection === dir) {
      isEditorSplit = false;
      activePaneIndex = 0;
    } else {
      isEditorSplit = true;
      splitDirection = dir;
      if (!pane1Path && openTabs.length > 1) {
        const otherTab = openTabs.find(t => t.path !== pane0Path) || openTabs[0];
        pane1Path = otherTab.path;
        pane1Content = otherTab.content;
      } else if (!pane1Path) {
        pane1Path = pane0Path;
        pane1Content = pane0Content;
      }
    }
  }

  function closeTab(e: MouseEvent, path: string) {
    e.stopPropagation();
    const idx = openTabs.findIndex(t => t.path === path);
    if (idx === -1) return;
    openTabs.splice(idx, 1);

    if (pane0Path === path) {
      const next = openTabs[0];
      pane0Path = next?.path || '';
      pane0Content = next?.content || '';
      if (next) editorRef0?.setValue(next.content);
    }
    if (pane1Path === path) {
      const next = openTabs[0];
      pane1Path = next?.path || '';
      pane1Content = next?.content || '';
      if (next) editorRef1?.setValue(next.content);
    }
  }

  async function saveActivePane() {
    const isFirst = activePaneIndex === 0;
    const activePath = isFirst ? pane0Path : pane1Path;
    const editorInstance = isFirst ? editorRef0 : editorRef1;

    if (!activePath) {
      statusMessage = 'No file active';
      return;
    }
    if (!isConnected) {
      statusMessage = 'Not connected';
      alert('Please connect to SSH before saving.');
      return;
    }

    const freshContent = editorInstance?.getValue() ?? (isFirst ? pane0Content : pane1Content);

    try {
      statusMessage = 'Saving...';
      await Ssh.writeFile({ path: activePath, content: freshContent });

      const tab = openTabs.find(t => t.path === activePath);
      if (tab) {
        tab.content = freshContent;
        tab.isDirty = false;
      }
      statusMessage = `Saved ${activePath.split('/').pop()}`;
      setTimeout(() => { statusMessage = `${sshHost}:${sshPort}`; }, 2000);
    } catch (err: any) {
      statusMessage = 'Save Error';
      alert('Failed to save: ' + (err?.message || err));
    }
  }

  function openCreateModal(type: 'newFile' | 'newFolder') {
    actionModalType = type;
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
    try {
      if (actionModalType === 'newFile') {
        const fullPath = `${rootPath}/${actionInputName.trim()}`;
        await Ssh.createFile({ path: fullPath });
        await openFile(fullPath);
      } else if (actionModalType === 'newFolder') {
        await Ssh.mkdir({ path: `${rootPath}/${actionInputName.trim()}` });
      } else if (actionModalType === 'rename' && actionTargetNode) {
        const oldPath = actionTargetNode.path;
        const newPath = `${oldPath.substring(0, oldPath.lastIndexOf('/'))}/${actionInputName.trim()}`;
        await Ssh.rename({ oldPath, newPath });
      }
      showActionModal = false;
      await loadRootDirectory();
    } catch (err: any) {
      alert(err?.message || err);
    }
  }

  async function deleteNode(node: FileNode) {
    if (!confirm(`Delete ${node.name}?`)) return;
    try {
      await Ssh.delete({ path: node.path, isDirectory: node.isDirectory });
      await loadRootDirectory();
    } catch (err: any) {
      alert(err?.message || err);
    }
  }

  function startSplitDrag(e: MouseEvent | TouchEvent) {
    const isVert = splitDirection === 'vertical';
    const rect = stageContainerEl.getBoundingClientRect();

    const onMove = (me: MouseEvent | TouchEvent) => {
      const clientX = 'touches' in me ? me.touches[0].clientX : me.clientX;
      const clientY = 'touches' in me ? me.touches[0].clientY : me.clientY;

      if (isVert) {
        const delta = clientX - rect.left;
        splitRatio = Math.max(15, Math.min(85, (delta / rect.width) * 100));
      } else {
        const delta = clientY - rect.top;
        splitRatio = Math.max(15, Math.min(85, (delta / rect.height) * 100));
      }
    };

    const onEnd = () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onEnd);
      window.removeEventListener('touchmove', onMove);
      window.removeEventListener('touchend', onEnd);
    };

    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onEnd);
    window.addEventListener('touchmove', onMove);
    window.addEventListener('touchend', onEnd);
  }

  let paletteResults = $derived(
    paletteQuery.trim() === ''
      ? allFlattenedFiles.slice(0, 10)
      : fuzzysort.go(paletteQuery, allFlattenedFiles).map(r => r.target).slice(0, 10)
  );
</script>

<div class="me-app">
  <header class="me-navbar">
    <div class="nav-brand">
      <button onclick={() => isSidebarOpen = !isSidebarOpen} class="icon-btn" title="Toggle Dock (Ctrl+B)">
        {#if isSidebarOpen}<PanelLeftClose size={15} />{:else}<PanelLeft size={15} />{/if}
      </button>
      <span class="brand-badge">ME</span>
      <span class="brand-name">MatrixEdit</span>
      <span class="nav-sep">│</span>
      <span class="nav-breadcrumb">{pane0Path ? pane0Path.replace(rootPath, '~') : 'Empty Workspace'}</span>
    </div>

    <div class="nav-actions">
      <button 
        onclick={() => toggleEditorSplit('vertical')} 
        class="icon-btn" 
        class:active-tool={isEditorSplit && splitDirection === 'vertical'} 
        title="Split Editor Right"
      >
        <Columns2 size={14} />
      </button>
      <button 
        onclick={() => toggleEditorSplit('horizontal')} 
        class="icon-btn" 
        class:active-tool={isEditorSplit && splitDirection === 'horizontal'} 
        title="Split Editor Down"
      >
        <Rows2 size={14} />
      </button>

      <button onclick={() => isPaletteOpen = true} class="btn-tool" title="Quick Open (Ctrl+P)">
        <Search size={13} />
        <span class="shortcut">Ctrl+P</span>
      </button>

      {#if !isConnected}
        <button onclick={() => showConnectDialog = true} class="btn-connect">Connect SSH</button>
      {:else}
        <button onclick={saveActivePane} class="icon-btn" title="Save File (Ctrl+S)"><Save size={14} /></button>
        <button onclick={loadRootDirectory} class="icon-btn" title="Refresh Tree"><RefreshCw size={14} /></button>
        <button onclick={disconnectSsh} class="icon-btn text-red-400" title="Disconnect SSH"><LogOut size={14} /></button>
      {/if}

      <button 
        onclick={() => isTerminalOpen = !isTerminalOpen} 
        class="icon-btn" 
        class:active-tool={isTerminalOpen}
        title="Toggle Terminal"
      >
        <TermIcon size={14} />
      </button>
      <button onclick={() => showSettingsModal = true} class="icon-btn" title="Settings (Ctrl+,)">
        <SettingsIcon size={14} />
      </button>
    </div>
  </header>

  <div class="me-tabbar">
    {#each openTabs as tab}
      <button 
        onclick={() => {
          if (activePaneIndex === 0) { 
            pane0Path = tab.path; 
            pane0Content = tab.content;
            editorRef0?.setValue(tab.content);
          } else { 
            pane1Path = tab.path; 
            pane1Content = tab.content;
            editorRef1?.setValue(tab.content);
          }
        }} 
        class="tab-pill" 
        class:active-tab={(activePaneIndex === 0 ? pane0Path : pane1Path) === tab.path}
      >
        <FileCode size={12} class="tab-icon" />
        <span class="tab-title">{tab.name}</span>
        {#if tab.isDirty}<span class="dirty-dot">●</span>{/if}
        <span onclick={(e) => closeTab(e, tab.path)} class="tab-close"><X size={11} /></span>
      </button>
    {/each}
  </div>

  <div class="me-workspace">
    {#if isSidebarOpen}
      <aside class="me-sidebar">
        <div class="sidebar-header">
          <span>Explorer</span>
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
                class="tree-node"
                style="padding-left: {depth * 14 + 6}px"
              >
                {#if node.isDirectory}
                  <span class="chevron">
                    {#if node.isOpen}<ChevronDown size={12} />{:else}<ChevronRight size={12} />{/if}
                  </span>
                  {#if node.isOpen}<FolderOpen size={13} class="icon-dir-open" />{:else}<Folder size={13} class="icon-dir" />{/if}
                {:else}
                  <span class="chevron-spacer"></span>
                  <FileCode size={13} class="icon-file" />
                {/if}
                <span class="node-name">{node.name}</span>

                <div class="node-actions" onclick={(e) => e.stopPropagation()}>
                  <button onclick={() => openRenameModal(node)} class="node-btn"><Edit2 size={11} /></button>
                  <button onclick={() => deleteNode(node)} class="node-btn text-red-400"><Trash2 size={11} /></button>
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
            <div class="empty-tree">Connect to explore files</div>
          {/if}
        </div>
      </aside>
    {/if}

    <main class="me-stage">
      <div 
        bind:this={stageContainerEl} 
        class="split-stage-container"
        class:is-vertical={splitDirection === 'vertical'}
        class:is-horizontal={splitDirection === 'horizontal'}
      >
        <div 
          class="editor-pane-wrapper" 
          class:active-pane={activePaneIndex === 0} 
          onclick={() => activePaneIndex = 0}
          style={isEditorSplit 
            ? (splitDirection === 'vertical' ? `width: ${splitRatio}%` : `height: ${splitRatio}%`) 
            : 'flex: 1;'}
        >
          {#if isEditorSplit}
            <div class="split-pane-header">
              <span>{pane0Path.split('/').pop() || 'untitled'}</span>
              <span class="pane-tag">Pane 1</span>
            </div>
          {/if}
          <Editor 
            bind:this={editorRef0}
            content={pane0Content}
            filename={pane0Path.split('/').pop() || 'main.go'}
            bind:vimMode={pane0VimMode}
            bind:cursorCoords={pane0Cursor}
            {settings}
            onContentChange={(val) => {
              pane0Content = val;
              const tab = openTabs.find(t => t.path === pane0Path);
              if (tab) {
                tab.content = val;
                tab.isDirty = true;
              }
            }}
          />
        </div>

        {#if isEditorSplit}
          <div 
            class="split-resizer" 
            class:resizer-vertical={splitDirection === 'vertical'}
            class:resizer-horizontal={splitDirection === 'horizontal'}
            onmousedown={startSplitDrag}
            ontouchstart={startSplitDrag}
            role="separator"
            tabindex="0"
          ></div>

          <div 
            class="editor-pane-wrapper" 
            class:active-pane={activePaneIndex === 1} 
            onclick={() => activePaneIndex = 1}
            style={splitDirection === 'vertical' ? `width: ${100 - splitRatio}%` : `height: ${100 - splitRatio}%`}
          >
            <div class="split-pane-header">
              <span>{pane1Path.split('/').pop() || 'untitled'}</span>
              <span class="pane-tag">Pane 2</span>
            </div>
            <Editor 
              bind:this={editorRef1}
              content={pane1Content}
              filename={pane1Path.split('/').pop() || 'main.go'}
              bind:vimMode={pane1VimMode}
              bind:cursorCoords={pane1Cursor}
              {settings}
              onContentChange={(val) => {
                pane1Content = val;
                const tab = openTabs.find(t => t.path === pane1Path);
                if (tab) {
                  tab.content = val;
                  tab.isDirty = true;
                }
              }}
            />
          </div>
        {/if}
      </div>

      <div class="mobile-keys">
        {#each ['ESC', ':', '/', 'i', 'w', 'b', '$', '0', '{', '}', ';'] as k}
          <button 
            onclick={() => (activePaneIndex === 0 ? editorRef0 : editorRef1)?.injectVimKey(k)} 
            class="key-item"
          >
            {k}
          </button>
        {/each}
      </div>

      <Terminal 
        sshPort={sshPort} 
        isConnected={isConnected} 
        bind:isOpen={isTerminalOpen}
        fontFamily={settings.font}
      />
    </main>

    {#if showSettingsModal}
      <div onclick={() => showSettingsModal = false} class="dialog-backdrop">
        <div onclick={(e) => e.stopPropagation()} class="settings-modal">
          <div class="settings-header">
            <span>Editor Settings</span>
            <button onclick={() => showSettingsModal = false} class="icon-btn"><X size={14} /></button>
          </div>
          <div class="settings-body">
            <div class="setting-item">
              <label>Theme</label>
              <select value={settings.theme} onchange={(e) => updateSetting('theme', e.currentTarget.value as AceTheme)}>
                <option value="one_dark">One Dark</option>
                <option value="dracula">Dracula</option>
                <option value="monokai">Monokai</option>
                <option value="nord_dark">Nord Dark</option>
                <option value="gruvbox">Gruvbox</option>
                <option value="tomorrow_night">Tomorrow Night</option>
              </select>
            </div>
            <div class="setting-item">
              <label>Font Family</label>
              <select value={settings.font} onchange={(e) => updateSetting('font', e.currentTarget.value as EditorFont)}>
                <option value="'Fira Code', monospace">Fira Code</option>
                <option value="'JetBrains Mono', monospace">JetBrains Mono</option>
                <option value="monospace">System Monospace</option>
              </select>
            </div>
            <div class="setting-item">
              <label>Font Size ({settings.fontSize}px)</label>
              <input type="range" min="10" max="22" value={settings.fontSize} oninput={(e) => updateSetting('fontSize', Number(e.currentTarget.value))} />
            </div>
            <div class="setting-row">
              <span>Line Wrapping</span>
              <input type="checkbox" checked={settings.lineWrapping} onchange={(e) => updateSetting('lineWrapping', e.currentTarget.checked)} />
            </div>
            <div class="setting-row">
              <span>Vim Modal Editing</span>
              <input type="checkbox" checked={settings.vimEnabled} onchange={(e) => updateSetting('vimEnabled', e.currentTarget.checked)} />
            </div>
          </div>
        </div>
      </div>
    {/if}

    {#if showActionModal}
      <div class="dialog-backdrop">
        <div class="dialog-card">
          <div class="dialog-title">File Operations</div>
          <input bind:value={actionInputName} onkeydown={(e) => e.key === 'Enter' && executeModalAction()} placeholder="Enter name..." />
          <div class="dialog-buttons">
            <button onclick={() => showActionModal = false} class="btn-cancel">Cancel</button>
            <button onclick={executeModalAction} class="btn-confirm">Submit</button>
          </div>
        </div>
      </div>
    {/if}

    {#if isPaletteOpen}
      <div onclick={() => isPaletteOpen = false} class="palette-backdrop">
        <div onclick={(e) => e.stopPropagation()} class="palette-window">
          <div class="palette-input-box">
            <Search size={14} class="palette-search-icon" />
            <input bind:value={paletteQuery} placeholder="Quick Open (Ctrl+P)..." class="palette-input" autofocus />
          </div>
          <div class="palette-results">
            {#each paletteResults as item}
              <button onclick={() => openFile(item)} class="palette-item">
                <FileCode size={13} class="icon-file" />
                <span>{item.split('/').pop()}</span>
                <span class="palette-filepath">{item.replace(rootPath, '~')}</span>
              </button>
            {/each}
          </div>
        </div>
      </div>
    {/if}

    {#if showConnectDialog}
      <div class="dialog-backdrop">
        <div class="dialog-card">
          <div class="dialog-title">Connect SSH</div>
          <div class="row">
            <input bind:value={sshHost} placeholder="Host" />
            <input bind:value={sshPort} type="number" style="width: 75px;" />
          </div>
          <input bind:value={sshUser} placeholder="Username" />
          <input bind:value={sshPass} type="password" placeholder="Password" />
          <input bind:value={rootPath} placeholder="Working Dir" />
          <div class="dialog-buttons">
            <button onclick={() => showConnectDialog = false} class="btn-cancel">Cancel</button>
            <button onclick={connectSsh} class="btn-confirm">Connect</button>
          </div>
        </div>
      </div>
    {/if}
  </div>

  <footer class="me-statusline">
    <div class="status-sec-left">
      <span class="mode-tag" class:insert={(activePaneIndex === 0 ? pane0VimMode : pane1VimMode) === 'INSERT'}>
        {settings.vimEnabled ? (activePaneIndex === 0 ? pane0VimMode : pane1VimMode) : 'EDIT'}
      </span>
      <span class="status-info">{statusMessage}</span>
      {#if isEditorSplit}
        <span class="pane-status-pill">PANE {activePaneIndex + 1}</span>
      {/if}
    </div>
    <div class="status-sec-right">
      <span>{settings.theme}</span>
      <span>UTF-8</span>
      <span>{(activePaneIndex === 0 ? pane0Path : pane1Path).split('.').pop() || 'plain'}</span>
      <span>Ln {(activePaneIndex === 0 ? pane0Cursor : pane1Cursor).split(':')[0]}, Col {(activePaneIndex === 0 ? pane0Cursor : pane1Cursor).split(':')[1]}</span>
    </div>
  </footer>
</div>

<style>
  .me-app { display: flex; flex-direction: column; width: 100vw; height: 100vh; background-color: #0d1117; color: #c9d1d9; overflow: hidden; }
  .me-navbar { display: flex; justify-content: space-between; align-items: center; height: 35px; background-color: #161b22; border-bottom: 1px solid #30363d; padding: 0 10px; }
  .nav-brand { display: flex; align-items: center; gap: 8px; font-size: 12px; }
  .brand-badge { background-color: #238636; color: #fff; font-weight: 700; padding: 1px 5px; border-radius: 3px; font-size: 10px; }
  .brand-name { font-weight: 600; color: #58a6ff; }
  .nav-sep { color: #30363d; }
  .nav-breadcrumb { color: #8b949e; font-size: 11px; }
  .nav-actions { display: flex; align-items: center; gap: 6px; }
  .btn-tool { display: flex; align-items: center; gap: 6px; background-color: #21262d; border: 1px solid #30363d; color: #8b949e; font-size: 11px; padding: 3px 8px; border-radius: 4px; cursor: pointer; }
  .shortcut { font-size: 9px; background-color: #30363d; color: #c9d1d9; padding: 1px 4px; border-radius: 2px; }
  .btn-connect { background-color: #238636; color: #fff; border: none; font-weight: 600; font-size: 11px; padding: 4px 8px; border-radius: 4px; cursor: pointer; }
  .icon-btn { background: transparent; border: none; color: #8b949e; padding: 4px; cursor: pointer; display: flex; }
  .icon-btn:hover { color: #c9d1d9; }
  .icon-btn.active-tool { color: #58a6ff; background-color: #21262d; border-radius: 3px; }
  
  .me-tabbar { display: flex; height: 29px; background-color: #0d1117; border-bottom: 1px solid #21262d; overflow-x: auto; }
  .tab-pill { display: flex; align-items: center; gap: 6px; height: 100%; background-color: #161b22; border: none; border-right: 1px solid #21262d; color: #8b949e; padding: 0 10px; font-size: 11px; cursor: pointer; }
  .tab-pill.active-tab { background-color: #0d1117; color: #58a6ff; border-top: 2px solid #58a6ff; }
  .dirty-dot { color: #d29922; font-size: 10px; }
  .tab-close:hover { background-color: #30363d; color: #fff; }
  
  .me-workspace { display: flex; flex: 1; overflow: hidden; position: relative; }
  .me-sidebar { width: 220px; background-color: #0d1117; border-right: 1px solid #21262d; display: flex; flex-direction: column; flex-shrink: 0; }
  .sidebar-header { display: flex; justify-content: space-between; align-items: center; padding: 6px 10px; font-size: 10px; font-weight: 700; text-transform: uppercase; color: #8b949e; border-bottom: 1px solid #21262d; }
  .sidebar-actions { display: flex; gap: 4px; }
  .tree-tool { background: transparent; border: none; color: #8b949e; cursor: pointer; padding: 1px 3px; }
  .tree-tool:hover { color: #58a6ff; }
  .sidebar-tree { flex: 1; overflow-y: auto; padding: 4px 0; }
  .tree-node { display: flex; align-items: center; gap: 4px; height: 25px; cursor: pointer; font-size: 11px; color: #c9d1d9; padding-right: 6px; position: relative; }
  .tree-node:hover { background-color: #161b22; }
  .tree-node:hover .node-actions { display: flex; }
  .chevron { width: 14px; display: flex; color: #8b949e; }
  .chevron-spacer { width: 14px; }
  .icon-dir { color: #58a6ff; }
  .icon-dir-open { color: #79c0ff; }
  .icon-file { color: #8b949e; }
  .node-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; text-align: left; }
  .node-actions { display: none; gap: 4px; margin-left: auto; }
  .node-btn { background: transparent; border: none; color: #8b949e; cursor: pointer; }
  .empty-tree { padding: 12px; font-size: 11px; color: #484f58; text-align: center; }

  .me-stage { display: flex; flex-direction: column; flex: 1; min-width: 0; background-color: #0d1117; }
  .split-stage-container { flex: 1; display: flex; overflow: hidden; position: relative; }
  .split-stage-container.is-vertical { flex-direction: row; }
  .split-stage-container.is-horizontal { flex-direction: column; }
  
  .editor-pane-wrapper { display: flex; flex-direction: column; overflow: hidden; border: 1px solid transparent; position: relative; min-width: 60px; min-height: 60px; }
  .editor-pane-wrapper.active-pane { border-color: #30363d; }
  .split-pane-header { display: flex; justify-content: space-between; align-items: center; background-color: #11161d; border-bottom: 1px solid #21262d; padding: 2px 8px; font-size: 10px; color: #8b949e; z-index: 5; }
  .pane-tag { font-size: 9px; background-color: #21262d; color: #58a6ff; padding: 0 4px; border-radius: 2px; }

  .split-resizer { background-color: #21262d; z-index: 10; }
  .split-resizer:hover { background-color: #1f6feb; }
  .resizer-vertical { width: 4px; height: 100%; cursor: col-resize; }
  .resizer-horizontal { height: 4px; width: 100%; cursor: row-resize; }

  .mobile-keys { display: flex; gap: 4px; padding: 3px 6px; background-color: #161b22; border-top: 1px solid #21262d; overflow-x: auto; }
  .key-item { background-color: #21262d; border: 1px solid #30363d; color: #c9d1d9; padding: 2px 8px; border-radius: 3px; font-weight: 600; font-size: 11px; }

  .me-statusline { display: flex; justify-content: space-between; align-items: center; height: 22px; background-color: #161b22; border-top: 1px solid #21262d; padding: 0 8px; font-size: 11px; }
  .status-sec-left, .status-sec-right { display: flex; align-items: center; gap: 8px; }
  .mode-tag { background-color: #1f6feb; color: #fff; font-weight: 700; padding: 0 5px; border-radius: 2px; }
  .mode-tag.insert { background-color: #238636; }
  .status-info { color: #8b949e; }
  .pane-status-pill { background-color: #30363d; color: #58a6ff; font-size: 9px; font-weight: 700; padding: 1px 4px; border-radius: 2px; }

  .dialog-backdrop, .palette-backdrop { position: absolute; inset: 0; background: rgba(0,0,0,0.65); display: flex; justify-content: center; align-items: center; z-index: 999; }
  .palette-backdrop { padding-top: 40px; align-items: flex-start; }
  .dialog-card, .palette-window, .settings-modal { background-color: #161b22; border: 1px solid #30363d; border-radius: 6px; width: 310px; padding: 14px; display: flex; flex-direction: column; gap: 10px; }
  .settings-modal { width: 340px; }
  .settings-header { display: flex; justify-content: space-between; align-items: center; font-weight: 700; color: #58a6ff; font-size: 13px; border-bottom: 1px solid #30363d; padding-bottom: 6px; }
  .settings-body { display: flex; flex-direction: column; gap: 12px; }
  .setting-item { display: flex; flex-direction: column; gap: 4px; }
  .setting-item label { font-size: 11px; color: #8b949e; }
  .setting-item select, .setting-item input[type="range"] { background-color: #0d1117; border: 1px solid #30363d; color: #c9d1d9; padding: 5px; border-radius: 3px; outline: none; }
  .setting-row { display: flex; justify-content: space-between; align-items: center; font-size: 12px; color: #c9d1d9; }
  .palette-window { width: 90%; max-width: 480px; padding: 0; }
  .palette-input-box { display: flex; align-items: center; padding: 8px 12px; border-bottom: 1px solid #30363d; gap: 8px; }
  .palette-search-icon { color: #58a6ff; }
  .palette-input { width: 100%; background: transparent; border: none; color: #fff; outline: none; }
  .palette-results { max-height: 220px; overflow-y: auto; }
  .palette-item { display: flex; align-items: center; gap: 8px; width: 100%; padding: 6px 12px; background: transparent; border: none; cursor: pointer; text-align: left; }
  .palette-item:hover { background-color: #21262d; }
  .palette-filepath { font-size: 10px; color: #8b949e; margin-left: auto; }
  .dialog-title { font-weight: 700; color: #58a6ff; font-size: 13px; border-bottom: 1px solid #30363d; padding-bottom: 4px; }
  .dialog-card input { width: 100%; background-color: #0d1117; border: 1px solid #30363d; color: #fff; padding: 4px 6px; font-size: 11px; border-radius: 3px; outline: none; }
  .row { display: flex; gap: 6px; }
  .dialog-buttons { display: flex; justify-content: flex-end; gap: 6px; }
  .btn-confirm { background-color: #238636; color: #fff; border: none; font-weight: 600; padding: 4px 10px; border-radius: 3px; cursor: pointer; }
  .btn-cancel { background-color: #21262d; color: #c9d1d9; border: 1px solid #30363d; padding: 4px 10px; border-radius: 3px; cursor: pointer; }
</style>