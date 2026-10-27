<script lang="ts">
  import { onMount } from 'svelte';
  import type { Action } from 'svelte/action';
  import { Terminal } from '@xterm/xterm';
  import { FitAddon } from '@xterm/addon-fit';
  import '@xterm/xterm/css/xterm.css';
  import { Ssh } from './ssh';
  import { X, GripHorizontal, Columns2 } from 'lucide-svelte';

  let { 
    sshPort = 8022, 
    isConnected = false, 
    isOpen = $bindable(true),
    fontFamily = "'Fira Code', monospace" 
  } = $props();

  interface SessionEntry {
    id: string;
    title: string;
    term?: Terminal;
    fit?: FitAddon;
  }

  let sessions = $state<SessionEntry[]>([
    { id: 'term-1', title: '1: bash' }
  ]);
  let height = $state(180);
  let isDragging = $state(false);

  const mountSessionAction: Action<HTMLDivElement, SessionEntry> = (node, session) => {
    const term = new Terminal({
      theme: {
        background: '#090d13',
        foreground: '#c9d1d9',
        cursor: '#58a6ff',
        selectionBackground: '#264f78'
      },
      fontFamily,
      fontSize: 12,
      lineHeight: 1.2,
      cursorBlink: true,
      convertEol: true
    });

    const fit = new FitAddon();
    term.loadAddon(fit);
    term.open(node);
    setTimeout(() => fit.fit(), 150);

    term.onData((data) => {
      if (isConnected) Ssh.sendTerminalInput({ id: session.id, data }).catch(() => {});
      else term.write(data);
    });

    session.term = term;
    session.fit = fit;

    return {
      destroy() {
        term.dispose();
      }
    };
  };

  onMount(() => {
    let removeListener: (() => void) | undefined;

    Ssh.addListener('terminalData', ({ id, chunk }) => {
      const match = sessions.find(s => s.id === id);
      if (match?.term) match.term.write(chunk);
    }).then(h => {
      removeListener = h.remove;
    });

    const handleWindowResize = () => {
      if (isOpen) sessions.forEach(s => s.fit?.fit());
    };
    window.addEventListener('resize', handleWindowResize);

    return () => {
      window.removeEventListener('resize', handleWindowResize);
      if (removeListener) removeListener();
      sessions.forEach(s => s.term?.dispose());
    };
  });

  // Fit automatically whenever drawer is opened
  $effect(() => {
    if (isOpen) {
      setTimeout(() => sessions.forEach(s => s.fit?.fit()), 100);
    }
  });

  async function addSplitSession() {
    const nextId = `term-${Date.now()}`;
    const newSession: SessionEntry = {
      id: nextId,
      title: `${sessions.length + 1}: bash`
    };
    sessions.push(newSession);

    if (isConnected) {
      await Ssh.openTerminalSession({ id: nextId }).catch(() => {});
    }

    setTimeout(() => sessions.forEach(s => s.fit?.fit()), 150);
  }

  async function closeSession(id: string) {
    if (sessions.length === 1) {
      isOpen = false;
      return;
    }
    const idx = sessions.findIndex(s => s.id === id);
    if (idx !== -1) {
      const target = sessions[idx];
      target.term?.dispose();
      sessions.splice(idx, 1);
      if (isConnected) Ssh.closeTerminalSession({ id }).catch(() => {});
      setTimeout(() => sessions.forEach(s => s.fit?.fit()), 150);
    }
  }

  function startResize(e: MouseEvent | TouchEvent) {
    isDragging = true;
    const startY = 'touches' in e ? e.touches[0].clientY : e.clientY;
    const initialHeight = height;

    const onMove = (me: MouseEvent | TouchEvent) => {
      if (!isDragging) return;
      const currentY = 'touches' in me ? me.touches[0].clientY : me.clientY;
      const delta = startY - currentY;
      height = Math.max(80, Math.min(window.innerHeight * 0.7, initialHeight + delta));
      sessions.forEach(s => s.fit?.fit());
    };

    const onEnd = () => {
      isDragging = false;
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onEnd);
      window.removeEventListener('touchmove', onMove);
      window.removeEventListener('touchend', onEnd);
      sessions.forEach(s => s.fit?.fit());
    };

    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onEnd);
    window.addEventListener('touchmove', onMove);
    window.addEventListener('touchend', onEnd);
  }
</script>

<div class="term-dock" class:hidden-dock={!isOpen} style="height: {height}px">
  <div 
    class="drag-handle" 
    onmousedown={startResize} 
    ontouchstart={startResize}
    role="separator"
    tabindex="0"
  >
    <GripHorizontal size={14} class="grip-icon" />
  </div>

  <div class="term-header">
    <div class="header-left">
      <span>TERMINAL (PTY {sshPort})</span>
      <button onclick={addSplitSession} class="tool-btn" title="Add Split Terminal">
        <Columns2 size={12} /> Split
      </button>
    </div>
    <button onclick={() => isOpen = false} class="close-btn"><X size={12} /></button>
  </div>

  <div class="split-viewport-container">
    {#each sessions as s, i (s.id)}
      <div class="term-pane" style="flex: 1;">
        <div class="pane-tabbar">
          <span>{s.title}</span>
          <button onclick={() => closeSession(s.id)} class="tab-close-btn"><X size={10} /></button>
        </div>
        <div class="canvas-mount" use:mountSessionAction={s}></div>
      </div>
      {#if i < sessions.length - 1}
        <div class="pane-divider"></div>
      {/if}
    {/each}
  </div>
</div>

<style>
  .term-dock {
    display: flex;
    flex-direction: column;
    background-color: #090d13;
    border-top: 1px solid #30363d;
    position: relative;
    width: 100%;
  }
  .hidden-dock {
    display: none !important;
  }
  .drag-handle {
    height: 6px;
    width: 100%;
    cursor: row-resize;
    display: flex;
    justify-content: center;
    align-items: center;
    background-color: #161b22;
    border-bottom: 1px solid #21262d;
    outline: none;
  }
  .drag-handle:hover { background-color: #1f6feb; }
  .grip-icon { color: #484f58; }
  .term-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 2px 8px;
    background-color: #161b22;
    font-size: 10px;
    color: #8b949e;
  }
  .header-left { display: flex; align-items: center; gap: 8px; }
  .tool-btn {
    display: flex;
    align-items: center;
    gap: 3px;
    background-color: #21262d;
    border: 1px solid #30363d;
    color: #c9d1d9;
    padding: 1px 6px;
    font-size: 10px;
    border-radius: 3px;
    cursor: pointer;
  }
  .tool-btn:hover { background-color: #30363d; color: #58a6ff; }
  .close-btn { background: transparent; border: none; color: #8b949e; cursor: pointer; }
  
  .split-viewport-container {
    display: flex;
    flex: 1;
    overflow: hidden;
    position: relative;
  }
  .term-pane {
    display: flex;
    flex-direction: column;
    overflow: hidden;
    min-width: 150px;
    height: 100%;
  }
  .pane-tabbar {
    display: flex;
    justify-content: space-between;
    align-items: center;
    background-color: #11161d;
    border-bottom: 1px solid #21262d;
    padding: 2px 8px;
    font-size: 10px;
    color: #8b949e;
  }
  .tab-close-btn { background: transparent; border: none; color: #6e7681; cursor: pointer; }
  .tab-close-btn:hover { color: #f85149; }
  .pane-divider { width: 1px; background-color: #30363d; }
  .canvas-mount {
    flex: 1;
    overflow: hidden;
    text-align: left !important;
  }
</style>
