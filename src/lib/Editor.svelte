<script lang="ts">
  import { onMount } from 'svelte';
  import { EditorState, Compartment } from '@codemirror/state';
  import { EditorView, keymap, lineNumbers } from '@codemirror/view';
  import { defaultKeymap, history, historyKeymap } from '@codemirror/commands';
  import { autocompletion, closeBrackets } from '@codemirror/autocomplete';
  import { vim, getCM } from '@replit/codemirror-vim';

  // Themes
  import { oneDark } from '@codemirror/theme-one-dark';
  import { 
    dracula, 
    tokyoNight, 
    nord, 
    githubDark, 
    materialDark 
  } from '@uiw/codemirror-themes-all';

  // Modern Language Grammars
  import { javascript } from '@codemirror/lang-javascript';
  import { go } from '@codemirror/lang-go';
  import { python } from '@codemirror/lang-python';
  import { rust } from '@codemirror/lang-rust';
  import { cpp } from '@codemirror/lang-cpp';
  import { java } from '@codemirror/lang-java';
  import { php } from '@codemirror/lang-php';
  import { html } from '@codemirror/lang-html';
  import { css } from '@codemirror/lang-css';
  import { json } from '@codemirror/lang-json';
  import { markdown } from '@codemirror/lang-markdown';
  import { xml } from '@codemirror/lang-xml';
  import { sql } from '@codemirror/lang-sql';
  import { yaml } from '@codemirror/lang-yaml';
  import { sass } from '@codemirror/lang-sass';
  import { vue } from '@codemirror/lang-vue';
  import { wast } from '@codemirror/lang-wast';

  // Legacy StreamParser Modes
  import { StreamLanguage } from '@codemirror/language';
  import { shell } from '@codemirror/legacy-modes/mode/shell';
  import { fortran } from '@codemirror/legacy-modes/mode/fortran';
  import { lua } from '@codemirror/legacy-modes/mode/lua';
  import { gas } from '@codemirror/legacy-modes/mode/gas';
  import { dockerFile } from '@codemirror/legacy-modes/mode/dockerfile';
  import { toml } from '@codemirror/legacy-modes/mode/toml';
  import { diff } from '@codemirror/legacy-modes/mode/diff';

  import type { EditorSettings } from './types';

  let { 
    content = $bindable(''), 
    filename = 'main.go', 
    vimMode = $bindable('NORMAL'),
    cursorCoords = $bindable('1:1'),
    settings = $bindable<EditorSettings>({
      theme: 'oneDark',
      font: "'Fira Code', monospace",
      fontSize: 13,
      tabSize: 4,
      lineWrapping: false,
      vimEnabled: true
    }),
    onContentChange = (val: string) => {}
  } = $props();

  let containerEl: HTMLDivElement;
  let view: EditorView;

  // Compartments for dynamic reconfiguration
  const langCompartment = new Compartment();
  const themeCompartment = new Compartment();
  const fontCompartment = new Compartment();
  const wrapCompartment = new Compartment();
  const vimCompartment = new Compartment();

  function resolveLanguage(file: string) {
    const ext = file.split('.').pop()?.toLowerCase() || '';
    switch (ext) {
      case 'go': return go();
      case 'js':
      case 'mjs':
      case 'cjs': return javascript();
      case 'ts':
      case 'mts': return javascript({ typescript: true });
      case 'jsx': return javascript({ jsx: true });
      case 'tsx': return javascript({ jsx: true, typescript: true });
      case 'py': return python();
      case 'rs': return rust();
      case 'c':
      case 'h':
      case 'cpp':
      case 'cc':
      case 'cxx':
      case 'hpp': return cpp();
      case 'java': return java();
      case 'php': return php();
      case 'html':
      case 'htm': return html();
      case 'css': return css();
      case 'scss': return sass();
      case 'json': return json();
      case 'md':
      case 'markdown': return markdown();
      case 'xml':
      case 'svg': return xml();
      case 'sql': return sql();
      case 'yaml':
      case 'yml': return yaml();
      case 'vue': return vue();
      case 'wat':
      case 'wast': return wast();

      // Legacy stream modes
      case 'sh':
      case 'bash':
      case 'zsh': return StreamLanguage.define(shell);
      case 'f':
      case 'f90':
      case 'f95': return StreamLanguage.define(fortran);
      case 'lua': return StreamLanguage.define(lua);
      case 's':
      case 'asm': return StreamLanguage.define(gas);
      case 'toml': return StreamLanguage.define(toml);
      case 'diff':
      case 'patch': return StreamLanguage.define(diff);
      default:
        if (file.toLowerCase() === 'dockerfile') return StreamLanguage.define(dockerFile);
        return [];
    }
  }

  function resolveThemeExtension(themeName: string) {
    switch (themeName) {
      case 'dracula': return dracula;
      case 'tokyoNight': return tokyoNight;
      case 'nord': return nord;
      case 'githubDark': return githubDark;
      case 'materialDark': return materialDark;
      case 'oneDark':
      default: return oneDark;
    }
  }

  function resolveFontExtension(font: string, size: number) {
    return EditorView.theme({
      '&': { height: '100%', fontSize: `${size}px`, fontFamily: font },
      '.cm-scroller': { fontFamily: font, lineHeight: '1.5' },
      '.cm-content': { caretColor: '#58a6ff' },
      '&.cm-focused .cm-cursor': { borderLeftColor: '#58a6ff' },
      '.cm-gutters': { borderRight: '1px solid #30363d' }
    });
  }

  onMount(() => {
    const startState = EditorState.create({
      doc: content,
      extensions: [
        lineNumbers(),
        history(),
        closeBrackets(),
        autocompletion(),
        vimCompartment.of(settings.vimEnabled ? vim({ status: false }) : []),
        themeCompartment.of(resolveThemeExtension(settings.theme)),
        fontCompartment.of(resolveFontExtension(settings.font, settings.fontSize)),
        langCompartment.of(resolveLanguage(filename)),
        wrapCompartment.of(settings.lineWrapping ? EditorView.lineWrapping : []),
        keymap.of([...defaultKeymap, ...historyKeymap]),
        EditorView.editable.of(true),
        EditorView.updateListener.of((update) => {
          if (settings.vimEnabled) {
            const cm = getCM(view);
            if (cm?.state?.vim) vimMode = cm.state.vim.mode.toUpperCase();
          } else {
            vimMode = 'EDIT';
          }

          if (update.selectionSet) {
            const head = update.state.selection.main.head;
            const line = update.state.doc.lineAt(head);
            cursorCoords = `${line.number}:${head - line.from + 1}`;
          }

          if (update.docChanged) {
            const val = view.state.doc.toString();
            content = val;
            onContentChange(val);
          }
        })
      ]
    });

    view = new EditorView({ state: startState, parent: containerEl });
    return () => view?.destroy();
  });

  // Hot-swap grammar on tab switch
  $effect(() => {
    if (view && filename) {
      view.dispatch({
        effects: langCompartment.reconfigure(resolveLanguage(filename))
      });
    }
  });

  // Hot-swap Theme
  $effect(() => {
    if (view && settings.theme) {
      view.dispatch({
        effects: themeCompartment.reconfigure(resolveThemeExtension(settings.theme))
      });
    }
  });

  // Hot-swap Font & Font Size
  $effect(() => {
    if (view && (settings.font || settings.fontSize)) {
      view.dispatch({
        effects: fontCompartment.reconfigure(resolveFontExtension(settings.font, settings.fontSize))
      });
    }
  });

  // Hot-swap Line Wrapping
  $effect(() => {
    if (view) {
      view.dispatch({
        effects: wrapCompartment.reconfigure(settings.lineWrapping ? EditorView.lineWrapping : [])
      });
    }
  });

  // Hot-swap Vim mode
  $effect(() => {
    if (view) {
      view.dispatch({
        effects: vimCompartment.reconfigure(settings.vimEnabled ? vim({ status: false }) : [])
      });
    }
  });

  // External document changes
  $effect(() => {
    if (view && content !== view.state.doc.toString()) {
      view.dispatch({
        changes: { from: 0, to: view.state.doc.length, insert: content }
      });
    }
  });

  export function injectVimKey(key: string) {
    if (!settings.vimEnabled) return;
    const cm = getCM(view);
    if (!cm) return;
    if (key === 'ESC') cm.handleKey('Esc');
    else cm.handleKey(key);
  }
</script>

<div class="editor-shell">
  <div bind:this={containerEl} class="cm-mount"></div>
</div>

<style>
  .editor-shell {
    display: flex;
    flex: 1;
    height: 100%;
    width: 100%;
    overflow: hidden;
    position: relative;
    background-color: #0d1117;
  }
  .cm-mount {
    flex: 1;
    height: 100%;
    width: 100%;
    overflow: hidden;
    text-align: left !important;
  }
  :global(.cm-editor) {
    height: 100% !important;
    outline: none !important;
    text-align: left !important;
  }
  :global(.cm-scroller) {
    overflow: auto;
  }
</style>
