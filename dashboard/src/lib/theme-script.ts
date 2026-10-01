/** Keys shared with `theme.ts` and `i18n.ts`. */
const STORAGE_KEY = "vc.theme";

/** Runs before first paint (inlined in <head>) so dark mode never flashes light. */
export const THEME_BOOT_SCRIPT = `(function(){try{var t=localStorage.getItem("${STORAGE_KEY}");var d=t==="dark"||(t!=="light"&&matchMedia("(prefers-color-scheme: dark)").matches);var e=document.documentElement;if(d)e.classList.add("dark");e.style.colorScheme=d?"dark":"light";var l=localStorage.getItem("vc.locale");if(l==="hi"||l==="en")e.lang=l;}catch(_){}})();`;
