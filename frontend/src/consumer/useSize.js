import { useEffect, useRef, useState } from 'react';

/** The pixel width of an element, kept current. Charts draw at 1:1 so their text never scales. */
export function useWidth(initial = 640) {
  const ref = useRef(null);
  const [width, setWidth] = useState(initial);
  useEffect(() => {
    const el = ref.current;
    if (!el) return undefined;
    const read = () => setWidth(Math.max(200, Math.floor(el.getBoundingClientRect().width)));
    read();
    if (typeof ResizeObserver === 'undefined') return undefined;
    const ro = new ResizeObserver(read);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return [ref, width];
}
