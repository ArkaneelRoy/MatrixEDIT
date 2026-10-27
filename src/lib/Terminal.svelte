<script lang="ts">
  import { onMount } from 'svelte';
  import { Terminal } from '@xterm/xterm';
  import { FitAddon } from '@xterm/addon-fit';
  import '@xterm/xterm/css/xterm.css';
  import { Ssh } from './ssh';
  import { X, GripHorizontal } from 'lucide-svelte';

  let { 
    sshPort = 8022, 
    isConnected = false, 
    isOpen = $bindable(true),
    fontFamily = "'Fira Code', monospace" 
  } = $props();

  let termParent: HTMLDivElement;
  let term: Terminal;
  let fitAddon: FitAddon;
  let height = $state(180);
  let isDragging = $state(false);

  onMount(() => {
    term = new Terminal({
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

    fitAddon = new FitAddon();
    term.loadAddon(fitAddon);
    term.open(termParent);
    setTimeout(() => fitAddon.fit(), 200);

    term.onData((data) => {
      if (isConnected) Ssh.sendTerminalInput({ data }).catch(() => {});
      else term.write(data);
    });

    try {
      Ssh.addListener('terminalData', ({ chunk }) => term.write(chunk));
    } catch (_) {}

    const handleResize = () => {
      if (isOpen && fitAddon) fitAddon.fit();
    };
    window.addEventListener('resize', handleResize);

    return () => {
      window.removeEventListener('resize', handleResize);
      term?.dispose();
    };
  });

  // Drag-to-Resize Logic
  function startResize(e: MouseEvent | TouchEvent) {
    isDragging = true;
    const startY = 'touches' in e ? e.touches[0].clientY : e.clientY;
    const initialHeight = height;

    const onMove = (moveEvent: MouseEvent | TouchEvent) => {
      if (!isDragging) return;
      const currentY = 'touches' in moveEvent ? moveEvent.touches[0].clientY : moveEvent.clientY;
      const delta = startY - currentY;
      height = Math.max(80, Math.min(window.innerHeight * 0.7, initialHeight + delta));
      fitAddon?.fit();
    };

    const onEnd = () => {
      isDragging = false;
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onEnd);
      window.removeEventListener('touchmove', onMove);
      window.removeEventListener('touchend', onEnd);
      fitAddon?.fit();
    };

    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onEnd);
    window.addEventListener('touchmove', onMove);
    window.addEventListener('touchend', onEnd);
  }
</script>

{#if isOpen}
  <div class="term-dock" style="height: {height}px">
    <!-- Draggable Handle Bar -->
    <div 
      class="drag-handle" 
      onmousedown={startResize}
      ontouchstart={startResize}
    >
      <GripHorizontal size={14} class="grip-icon" />
    </div>

    <!-- Header -->
    <div class="term-header">
      <span>TERMINAL (PTY {sshPort})</span>
      <button onclick={() => isOpen = false} class="close-btn"><X size={12} /></button>
    </div>

    <!-- Canvas -->
    <div bind:this={termParent} class="term-viewport"></div>
  </div>
{/if}

<style>
  .term-dock {
    display: flex;
    flex-direction: column;
    background-color: #090d13;
    border-top: 1px solid #30363d;
    position: relative;
    width: 100%;
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
  }
  .drag-handle:hover {
    background-color: #1f6feb;
  }
  .grip-icon {
    color: #484f58;
  }
  .term-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 2px 8px;
    background-color: #161b22;
    font-size: 10px;
    color: #8b949e;
  }
  .close-btn { background: transparent; border: none; color: #8b949e; cursor: pointer; }
  .term-viewport {
    flex: 1;
    overflow: hidden;
    text-align: left !important;
  }
</style>
