function toggleAddItemForm() {
    const main = document.getElementById('main-layout');
    const form = document.getElementById('add-item-form');
    const btn = document.getElementById('toggle-add-form-btn');
    const icon = document.getElementById('toggle-add-form-icon');
    const isHidden = form.classList.toggle('hidden');
    const isExpanded = !isHidden;

    main.setAttribute('data-expanded', String(isExpanded));
    btn.setAttribute('aria-expanded', String(isExpanded));
    btn.setAttribute('title', isExpanded ? 'Minimize Add to Collection form' : 'Expand Add to Collection form');
    icon.classList.toggle('rotate-45', isExpanded);
}

async function lookupMetadata() {
    const form = document.getElementById('add-item-form');
    const type = document.getElementById('add-media-type').value;
    const query = document.getElementById('lookup-query').value.trim();
    const status = document.getElementById('lookup-status');

    if (!query) {
        status.textContent = 'Enter an ISBN (for books) or movie title first.';
        status.className = 'text-[11px] text-amber-400';
        return;
    }

    status.textContent = 'Looking up metadata...';
    status.className = 'text-[11px] text-slate-400';

    try {
        const resp = await fetch(`/api/lookup?type=${encodeURIComponent(type)}&q=${encodeURIComponent(query)}`);
        const data = await resp.json();

        if (!data.found) {
            status.textContent = data.message || 'No matching metadata found.';
            status.className = 'text-[11px] text-amber-400';
            return;
        }

        const setField = (name, value) => {
            if (value !== undefined && value !== null && value !== '') {
                const el = form.querySelector(`[name="${name}"]`);
                if (el) el.value = value;
            }
        };

        setField('title', data.title);
        setField('creator', data.creator);
        setField('releaseYear', data.releaseYear);
        setField('genre', data.genre);
        setField('identifierCode', data.identifierCode);
        setField('attrKey1', data.attrKey1);
        setField('attrVal1', data.attrVal1);
        setField('attrKey2', data.attrKey2);
        setField('attrVal2', data.attrVal2);

        status.textContent = `Auto-filled from ${data.source}!`;
        status.className = 'text-[11px] text-emerald-400';
    } catch (err) {
        status.textContent = 'Lookup failed: ' + err.message;
        status.className = 'text-[11px] text-rose-400';
    }
}
