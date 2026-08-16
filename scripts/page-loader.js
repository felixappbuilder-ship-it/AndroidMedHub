// scripts/page-loader.js

/**
 * Loads and parses a page HTML file, extracting metadata and the root <section>.
 * @param {string} pageName - The page name (e.g., 'dashboard', 'exam')
 * @returns {Promise<Object>} Page descriptor with metadata and HTML.
 * @throws {Error} If the page cannot be fetched or is invalid.
 */
export async function loadPage(pageName) {
    const url = `/pages/${pageName}.html`;
    const response = await fetch(url);
    if (!response.ok) {
        throw new Error(`Page not found: ${url} (HTTP ${response.status})`);
    }
    const html = await response.text();

    const parser = new DOMParser();
    const doc = parser.parseFromString(html, 'text/html');
    const section = doc.querySelector('section[data-page]');
    if (!section) {
        throw new Error(`Invalid page: missing <section data-page> in ${url}`);
    }

    const metadata = {
        page: section.dataset.page || pageName,
        script: section.dataset.script || `/scripts/pages/${pageName}.js`,
        style: section.dataset.style || `/css/${pageName}.css`,   // ✅ fixed template literal
        title: section.dataset.title || pageName,
        auth: section.dataset.auth || 'none',
        cache: section.dataset.cache === 'true',
        transition: section.dataset.transition || null,
        preload: section.dataset.preload === 'true',
        rootElement: section,
        html: section.outerHTML,
    };

    if (!metadata.script) {
        throw new Error(`Page "${pageName}" has no data-script attribute.`);
    }

    return metadata;
}