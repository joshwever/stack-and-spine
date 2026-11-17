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

