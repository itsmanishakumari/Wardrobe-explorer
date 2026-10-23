<script lang="ts">
  import { onDestroy, onMount, tick } from 'svelte'

  type Category =
    | 'TOP'
    | 'BOTTOM'
    | 'ONE_PIECE'
    | 'SHOES'
    | 'LAYER'
    | 'ACCESSORY'

  type Garment = {
    id: number
    name: string
    category: Category
    colors: string
    pattern: string
    sleeves: string
    season: string
    style: string
    notes: string
    imageId: string | null
    imageUrl: string | null
    createdAt: string
  }
  type Draft = Omit<Garment, 'id' | 'imageUrl' | 'createdAt'>
  type Outfit = {
    title: string
    explanation: string
    garmentIds: number[]
  }
  type Status = {
    ollamaAvailable: boolean
    visionModel: string
    textModel: string
    visionModelReady: boolean
    textModelReady: boolean
    message: string
  }

  const categories: { value: Category | 'ALL'; label: string }[] = [
    { value: 'ALL', label: 'Everything' },
    { value: 'TOP', label: 'Tops' },
    { value: 'BOTTOM', label: 'Bottoms' },
    { value: 'ONE_PIECE', label: 'One-pieces' },
    { value: 'LAYER', label: 'Layers' },
    { value: 'SHOES', label: 'Shoes' },
    { value: 'ACCESSORY', label: 'Accessories' },
  ]

  const blankDraft = (): Draft => ({
    name: '',
    category: 'TOP',
    colors: '',
    pattern: '',
    sleeves: '',
    season: '',
    style: '',
    notes: '',
    imageId: null,
  })

  const labelCategory = (value: Category) =>
    ({
      TOP: 'Top',
      BOTTOM: 'Bottom',
      ONE_PIECE: 'One-piece',
      LAYER: 'Layer',
      SHOES: 'Shoes',
      ACCESSORY: 'Accessory',
    })[value]
  let garments = $state<Garment[]>([])
  let loading = $state(true)
  let loadError = $state('')
  let search = $state('')
  let activeCategory = $state<Category | 'ALL'>('ALL')
  let status = $state<Status | null>(null)
  let statusError = $state('')
  let statusLoading = $state(false)
  let formOpen = $state(false)
  let dialog = $state<HTMLDialogElement>()
  let editingId = $state<number | null>(null)
  let draft = $state<Draft>(blankDraft())
  let saving = $state(false)
  let analyzing = $state(false)
  let uploading = $state(false)
  let formError = $state('')
  let imageError = $state('')
  let imageDecisionRequired = $state(false)
  let analysisMessage = $state('')
  let previewUrl = $state('')
  let pendingFile = $state<File | null>(null)
  let originalImageId = $state<string | null>(null)
  let originalImageUrl = $state<string | null>(null)
  let deleteConfirmId = $state<number | null>(null)
  let deletingId = $state<number | null>(null)
  let deleteError = $state('')
  let occasion = $state('Everyday')
  let outfitCount = $state(2)
  let outfits = $state<Outfit[]>([])
  let outfitMessage = $state('')
  let outfitError = $state('')
  let generating = $state(false)
  let inventoryVersion = 0
  const busy = $derived(saving || analyzing || uploading)
  const filteredGarments = $derived(
    garments.filter(
      (item) =>
        (activeCategory === 'ALL' || activeCategory === item.category) &&
        (!search.trim() ||
          [
            item.name,
            item.category,
            item.colors,
            item.pattern,
            item.style,
            item.season,
            item.notes,
          ]
            .join(' ')
            .toLowerCase()
            .includes(search.trim().toLowerCase())),
    ),
  )

  async function request<T>(url: string, init?: RequestInit): Promise<T> {
    let response: Response
    try {
      response = await fetch(url, init)
    } catch {
      throw new Error(
        'Could not reach the wardrobe server. Check that the local backend is running and try again.',
      )
    }
    if (!response.ok) {
      let message = `Request failed (${response.status}). Please try again.`
      try {
        const body = (await response.json()) as { message?: string }
        if (body.message) message = body.message
      } catch {
        // Keep the fallback message if the response isn't JSON.
      }
      throw new Error(message)
    }
    if (response.status === 204) return undefined as T
    return await response.json() as T
  }
  async function loadGarments() {
    loading = true; loadError = ''
    try {
      garments = await request<Garment[]>('/api/garments')
      invalidateOutfits()
    } catch (error) {
      loadError = error instanceof Error ? error.message : 'Your wardrobe could not be loaded.'
    } finally {
      loading = false
    }
  }
  async function refreshStatus() {
    statusLoading = true; statusError = ''
    try {
      status = await request<Status>('/api/status')
    } catch (error) {
      status = null
      statusError = error instanceof Error ? error.message : 'Assistant status is unavailable.'
    } finally {
      statusLoading = false
    }
  }
  onMount(() => {
    void loadGarments()
    void refreshStatus()
  })
  onDestroy(() => setPreview(''))
  function setPreview(url: string) {
    if (previewUrl.startsWith('blob:')) URL.revokeObjectURL(previewUrl)
    previewUrl = url
  }
  async function openForm(item?: Garment) {
    editingId = item?.id ?? null
    draft = item ? { name: item.name, category: item.category, colors: item.colors, pattern: item.pattern, sleeves: item.sleeves, season: item.season, style: item.style, notes: item.notes, imageId: item.imageId } : blankDraft()
    originalImageId = item?.imageId ?? null
    originalImageUrl = item?.imageUrl ?? null
    setPreview(item?.imageUrl ?? '')
    pendingFile = null

    formError = ''
    imageError = ''
    analysisMessage = ''
    imageDecisionRequired = false
    formOpen = true

    await tick()
    dialog?.showModal()
  }
  function closeForm() {
    if (busy) return
    dialog?.close()
    resetForm()
  }
  function resetForm() {
    formOpen = false
    pendingFile = null
    setPreview('')
  }
  function dialogCancel(event: Event) {
    if (busy) event.preventDefault()
  }
  function dialogClick(event: MouseEvent) {
    if (event.target === dialog && !busy) closeForm()
  }
  async function uploadFile(file: File) {
    if (busy) return
    if (!['image/jpeg', 'image/png'].includes(file.type)) {
      imageError = 'Choose a JPEG or PNG image.'
      imageDecisionRequired = true
      return
    }
    if (file.size > 10 * 1024 * 1024) {
      imageError = 'That image is larger than 10 MB. Choose a smaller file.'
      imageDecisionRequired = true
      return
    }

    pendingFile = file
    setPreview(URL.createObjectURL(file))
    draft.imageId = null
    imageError = ''
    imageDecisionRequired = false
    uploading = true

    try {
      const body = new FormData()
      body.append('file', file)

      const image = await request<{ imageId: string; imageUrl: string }>('/api/images', {
        method: 'POST',
        body,
      })

      draft.imageId = image.imageId
      setPreview(image.imageUrl)
      pendingFile = null
      imageDecisionRequired = false
    } catch (error) {
      imageError = error instanceof Error ? error.message : 'The image could not be uploaded.'
      imageDecisionRequired = true
    } finally {
      uploading = false
    }
  }
  function onFileChange(event: Event) {
    const input = event.currentTarget as HTMLInputElement
    const file = input.files?.[0]

    if (file) void uploadFile(file)
    input.value = ''
  }
  function keepOriginalPhoto() {
    draft.imageId = originalImageId
    setPreview(originalImageUrl ?? '')
    pendingFile = null
    imageError = ''
    imageDecisionRequired = false
  }
  function continueWithoutPhoto() {
    draft.imageId = null
    setPreview('')
    pendingFile = null
    imageError = ''
    imageDecisionRequired = false
  }
  async function analyzePhoto() {
    if (!draft.imageId || busy) return

    analyzing = true
    formError = ''
    analysisMessage = ''
    try {
      const result = await request<{ draft: Partial<Draft>; message: string }>('/api/analysis', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ imageId: draft.imageId }),
      })

      draft = { ...draft, ...result.draft, imageId: draft.imageId }
      analysisMessage =
        result.message || 'Review the suggested details, then save when they look right.'
    } catch (error) {
      formError = error instanceof Error ? error.message : 'Photo analysis could not be completed.'
    } finally {
      analyzing = false
    }
  }
  async function saveGarment(event: SubmitEvent) {
    event.preventDefault()
    if (busy || imageDecisionRequired) return
    saving = true
    formError = ''

    const payload = {
      ...draft,
      name: draft.name.trim(),
      colors: draft.colors.trim(),
      pattern: draft.pattern.trim(),
      sleeves: draft.sleeves.trim(),
      season: draft.season.trim(),
      style: draft.style.trim(),
      notes: draft.notes.trim(),
    }

    try {
      const url = editingId ? `/api/garments/${editingId}` : '/api/garments'
      const method = editingId ? 'PUT' : 'POST'
      const saved = await request<Garment>(url, {
        method,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      })

      garments = editingId
        ? garments.map((item) => (item.id === saved.id ? saved : item))
        : [...garments, saved]
      invalidateOutfits()
      saving = false
      closeForm()
    } catch (error) {
      formError = error instanceof Error
        ? error.message
        : 'This garment could not be saved. Your draft is still here.'
      saving = false
    }
  }
  async function deleteGarment(item: Garment) {
    if (deletingId !== null) return
    deletingId = item.id; deleteError = ''
    try { await request<void>(`/api/garments/${item.id}`, { method: 'DELETE' }); garments = garments.filter((garment) => garment.id !== item.id); invalidateOutfits(); deleteConfirmId = null }
    catch (error) { deleteError = error instanceof Error ? error.message : 'This garment could not be deleted.' } finally { deletingId = null }
  }
  function invalidateOutfits() {
    inventoryVersion += 1
    outfits = []; outfitError = ''
    outfitMessage = generating ? 'Your wardrobe changed. Find an outfit again for updated ideas.' : ''
  }
  async function generateOutfits(event: SubmitEvent) {
    event.preventDefault(); if (generating) return
    const requestedVersion = inventoryVersion
    generating = true; outfitError = ''; outfitMessage = ''; outfits = []
    try {
      const result = await request<{ outfits: Outfit[]; message: string }>('/api/outfits', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ occasion: occasion.trim(), count: outfitCount }) })
      if (requestedVersion !== inventoryVersion) return
      outfits = result.outfits ?? []; outfitMessage = result.message ?? ''
    } catch (error) {
      if (requestedVersion === inventoryVersion) outfitError = error instanceof Error ? error.message : 'Outfit ideas could not be generated.'
    } finally { generating = false }
  }
  function garmentFor(id: number) { return garments.find((item) => item.id === id) }
</script>

<svelte:head><meta name="theme-color" content="#f7f5ef" /><meta name="description" content="A thoughtful, local-first space to edit your wardrobe and plan what to wear." /></svelte:head>

<div class="app-shell">
  <header class="topbar">
    <a class="wordmark" href="#top" aria-label="The Daily Edit home"><span class="brand-mark" aria-hidden="true">d</span><span>the daily edit</span></a>
    <nav aria-label="Main navigation"><a class="nav-link active" href="#wardrobe">My wardrobe</a><a class="nav-link" href="#outfits">Outfit ideas</a></nav>
    <div class="local-note"><span class="local-dot"></span> Your wardrobe stays on this device</div>
  </header>
  <main id="top">
    <section class="hero" aria-labelledby="page-title">
      <div class="hero-copy"><p class="eyebrow">A little more you, every day</p><h1 id="page-title">Get dressed with<br /><em>what you love.</em></h1><p class="hero-description">A calmer way to see what you own, find new combinations, and make getting dressed feel like yours.</p><a class="text-link" href="#wardrobe">Explore your wardrobe <span aria-hidden="true">↘</span></a></div>
      <div class="hero-art" aria-hidden="true"><div class="art-sun"></div><div class="art-caption">A considered<br />everyday wardrobe</div><div class="art-shape art-shape-one"></div><div class="art-shape art-shape-two"></div><div class="art-shape art-shape-three"></div><div class="art-line"></div><span class="art-star">✳</span></div>
    </section>
    <section class="wardrobe-section section-wrap" id="wardrobe" aria-labelledby="wardrobe-title">
      <div class="section-heading"><div><p class="eyebrow">Your personal collection</p><h2 id="wardrobe-title">The wardrobe</h2></div><div class="wardrobe-actions"><span class="item-count"><strong>{garments.length}</strong> {garments.length === 1 ? 'piece' : 'pieces'}</span><button class="button button-primary" type="button" onclick={() => openForm()}><span aria-hidden="true">＋</span> Add a piece</button></div></div>
      <div class="toolbar"><div class="filters" role="group" aria-label="Filter wardrobe by category">{#each categories as category (category.value)}<button type="button" class="filter-chip" class:filter-active={activeCategory === category.value} aria-pressed={activeCategory === category.value} onclick={() => activeCategory = category.value}>{category.label}</button>{/each}</div><label class="search-box"><span class="sr-only">Search wardrobe</span><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.8" cy="10.8" r="6.4"></circle><path d="m16 16 4.2 4.2"></path></svg><input bind:value={search} type="search" placeholder="Search your pieces" /></label></div>
      {#if loading}<div class="state-panel" role="status"><span class="spinner"></span><p>Opening your wardrobe…</p></div>
      {:else if loadError}<div class="state-panel state-error" role="alert"><div class="state-icon">!</div><h3>We couldn’t open your wardrobe</h3><p>{loadError}</p><button class="button button-outline" type="button" onclick={() => loadGarments()}>Try again</button></div>
      {:else if garments.length === 0}<div class="empty-state"><div class="empty-illustration" aria-hidden="true"><div class="hanger-hook"></div><div class="hanger"></div><div class="empty-spark spark-a">✳</div><div class="empty-spark spark-b">·</div></div><p class="eyebrow">A fresh start</p><h3>Your wardrobe, your way.</h3><p class="empty-copy">Your collection is ready when you are. Add a piece you love, with a photo or just a few details.</p><button class="button button-primary" type="button" onclick={() => openForm()}>Add your first piece <span aria-hidden="true">↗</span></button></div>
      {:else if filteredGarments.length === 0}<div class="no-results"><span aria-hidden="true">⌕</span><h3>No pieces found</h3><p>Try another search or category.</p><button class="text-link" type="button" onclick={() => { search = ''; activeCategory = 'ALL' }}>Clear filters</button></div>
      {:else}<div class="garment-grid">{#each filteredGarments as item (item.id)}<article class="garment-card"><div class="garment-image-wrap">{#if item.imageUrl}<img class="garment-image" src={item.imageUrl} alt={item.name} loading="lazy" />{:else}<div class="garment-placeholder" aria-label="No photo"><span class="placeholder-hanger" aria-hidden="true">⌁</span><span>no photo</span></div>{/if}<span class="category-tag">{labelCategory(item.category)}</span></div><div class="garment-details"><div class="garment-title-row"><div><h3>{item.name}</h3><p>{[item.colors, item.pattern].filter(Boolean).join(' · ') || labelCategory(item.category)}</p></div><button class="icon-button" type="button" aria-label={`Edit ${item.name}`} title={`Edit ${item.name}`} onclick={() => openForm(item)}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m14 5 5 5M4 20l4.2-.9L19 8.3a2.1 2.1 0 0 0-3-3L5.2 16.1 4 20Z"></path></svg></button></div>{#if item.style || item.season}<div class="garment-meta">{#if item.style}<span>{item.style}</span>{/if}{#if item.season}<span>{item.season}</span>{/if}</div>{/if}{#if deleteConfirmId === item.id}<div class="delete-confirm" aria-live="polite"><span>Remove this piece?</span><button type="button" class="text-button danger-text" disabled={deletingId === item.id} onclick={() => deleteGarment(item)}>{deletingId === item.id ? 'Removing…' : 'Remove'}</button><button type="button" class="text-button" onclick={() => { deleteConfirmId = null; deleteError = '' }}>Keep</button></div>{#if deleteError}<p class="inline-error" role="alert">{deleteError}</p>{/if}{:else}<div class="card-footer"><span>{item.notes || 'A favourite in the making'}</span><button class="text-button remove-trigger" type="button" onclick={() => { deleteConfirmId = item.id; deleteError = '' }}>Remove</button></div>{/if}</div></article>{/each}</div>{/if}
    </section>
    <section class="assistant-band" aria-label="Local wardrobe assistant"><div class="assistant-mark" aria-hidden="true">✳</div><div class="assistant-copy"><p class="eyebrow">A helpful extra, never a requirement</p><h2>A little help from your local assistant.</h2><p>Photo details and outfit ideas can be suggested by Ollama on your computer. You always review garment details before saving a piece.</p></div><div class="assistant-status"><div class="status-heading"><span class="status-dot" class:status-online={status?.ollamaAvailable}></span><span>{statusLoading ? 'Checking assistant…' : status?.ollamaAvailable ? 'Ollama connected' : 'Assistant not connected'}</span><button class="refresh-button" type="button" aria-label="Refresh assistant status" title="Refresh assistant status" disabled={statusLoading} onclick={() => refreshStatus()}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 7v5h-5M4 17v-5h5"></path><path d="M5.6 9a7 7 0 0 1 11.8-2L20 12M4 12l2.6 5a7 7 0 0 0 11.8-2"></path></svg></button></div>{#if status}<p class="model-info">Vision · {status.visionModel || 'not configured'}{status.visionModelReady ? ' ✓' : ''}<br />Outfits · {status.textModel || 'not configured'}{status.textModelReady ? ' ✓' : ''}</p>{#if status.message}<p class="model-info">{status.message}</p>{/if}{:else if statusError}<p class="model-info">{statusError}</p>{:else}<p class="model-info">Connect a local model to try optional AI features.</p>{/if}<p class="assistant-hint">No assistant runs until you ask.</p></div></section>
    <section class="outfit-section section-wrap" id="outfits" aria-labelledby="outfit-title"><div class="outfit-intro"><p class="eyebrow">Make room for a new combination</p><h2 id="outfit-title">What feels like <em>today?</em></h2><p>Get considered outfit ideas from the pieces you already own.</p></div><form class="outfit-form" onsubmit={generateOutfits}><label class="field-label" for="occasion">The occasion</label><input class="text-input" id="occasion" bind:value={occasion} maxlength="200" placeholder="A slow Sunday, dinner out…" required /><div class="outfit-form-bottom"><label class="field-label" for="outfit-count">Ideas<select id="outfit-count" bind:value={outfitCount}>{#each [1, 2, 3] as count}<option value={count}>{count}</option>{/each}</select></label><button class="button button-dark" type="submit" disabled={generating || !occasion.trim()}>{#if generating}<span class="button-spinner"></span> Finding ideas…{:else}Find an outfit <span aria-hidden="true">↗</span>{/if}</button></div></form>
      {#if outfitError}<div class="notice notice-error" role="alert"><strong>Couldn’t find outfit ideas.</strong> {outfitError}</div>{/if}{#if outfitMessage && !outfits.length}<div class="notice" role="status">{outfitMessage}</div>{/if}
      {#if outfits.length}<div class="outfit-results" aria-live="polite">{#each outfits as outfit, index (outfit.title + index)}<article class="outfit-card"><div class="outfit-card-top"><span class="outfit-number">0{index + 1}</span><div><h3>{outfit.title}</h3><p>{outfit.explanation}</p></div></div><div class="outfit-look" role="group" aria-label={`Pieces in ${outfit.title}`}>{#each outfit.garmentIds as id (id)}{@const item = garmentFor(id)}{#if item}<div class="look-piece"><div class="look-image">{#if item.imageUrl}<img src={item.imageUrl} alt={item.name} loading="lazy" />{:else}<div class="look-placeholder" aria-label={`No image for ${item.name}`}><span aria-hidden="true">✳</span></div>{/if}<span class="look-category">{labelCategory(item.category)}</span></div><span class="look-name">{item.name}</span></div>{/if}{/each}</div></article>{/each}</div>{:else if !outfitError && !outfitMessage}<p class="outfit-footnote">Your ideas will only include saved pieces from your wardrobe.</p>{/if}
    </section>
  </main>
  <footer class="footer"><a class="wordmark" href="#top"><span class="brand-mark" aria-hidden="true">d</span><span>the daily edit</span></a><p>Made for the everyday, by you.</p><span class="footer-local"><span class="local-dot"></span> Private & local</span></footer>
</div>

{#if formOpen}
  <dialog
    bind:this={dialog}
    class="edit-dialog"
    aria-labelledby="dialog-title"
    oncancel={dialogCancel}
    onclose={resetForm}
    onclick={dialogClick}
  >
    <div class="dialog-header">
      <div>
        <p class="eyebrow">{editingId ? 'A considered edit' : 'Add to your collection'}</p>
        <h2 id="dialog-title">{editingId ? 'Edit your piece' : 'Add a piece'}</h2>
      </div>
      <button
        class="icon-button close-button"
        type="button"
        aria-label="Close dialog"
        disabled={busy}
        onclick={closeForm}
      >
        ×
      </button>
    </div>

    <form class="garment-form" onsubmit={saveGarment}>
      <div class="photo-uploader">
        {#if previewUrl}
          <img class="photo-preview" src={previewUrl} alt="Selected garment preview" />
        {:else}
          <div class="photo-placeholder" aria-hidden="true">
            <svg viewBox="0 0 48 48">
              <path d="M24 10a4 4 0 1 1 4 4M24 14 8 23l4 5 5-3v13h22V25l5 3 4-5-16-9"></path>
            </svg>
          </div>
        {/if}

        <div class="photo-copy">
          <strong>{previewUrl ? 'Your photo' : 'Add a photo, if you like'}</strong>
          <span>JPEG or PNG · up to 10 MB</span>
          <div class="photo-actions">
            <label class="button button-outline upload-button">
              {uploading ? 'Uploading…' : previewUrl ? 'Choose another' : 'Choose a photo'}
              <input
                type="file"
                accept="image/jpeg,image/png"
                disabled={busy}
                onchange={onFileChange}
              />
            </label>
            {#if previewUrl && !uploading}
              <button
                type="button"
                class="text-button"
                disabled={busy}
                onclick={continueWithoutPhoto}
              >
                Remove photo
              </button>
            {/if}
          </div>
        </div>
      </div>

      {#if imageError}
        <div class="form-alert" role="alert">
          <span>{imageError}</span>
          <div>
            {#if editingId && originalImageId}
              <button type="button" class="text-button" onclick={keepOriginalPhoto}>
                Keep current photo
              </button>
            {/if}
            {#if !editingId}
              <button type="button" class="text-button" onclick={continueWithoutPhoto}>
                Continue without photo
              </button>
            {/if}
            {#if pendingFile}
              <button
                type="button"
                class="text-button"
                disabled={busy}
                onclick={() => uploadFile(pendingFile!)}
              >
                Try upload again
              </button>
            {/if}
          </div>
        </div>
      {/if}

      {#if draft.imageId}
        <div class="analysis-row">
          <div>
            <strong>Want a head start?</strong>
            <span>Ask your local assistant to suggest details. You can edit every field.</span>
          </div>
          <button
            type="button"
            class="button button-outline analyze-button"
            disabled={busy}
            onclick={analyzePhoto}
          >
            {#if analyzing}
              <span class="button-spinner"></span> Looking…
            {:else}
              Suggest details <span aria-hidden="true">✳</span>
            {/if}
          </button>
        </div>
      {/if}

      {#if analysisMessage}
        <p class="form-success" role="status">{analysisMessage}</p>
      {/if}
      {#if formError}
        <p class="form-alert" role="alert">{formError}</p>
      {/if}

      <div class="form-fields">
        <label class="field-label field-wide" for="garment-name">
          Piece name <span class="required-mark">*</span>
          <input
            id="garment-name"
            class="text-input"
            bind:value={draft.name}
            maxlength="120"
            placeholder="e.g. Relaxed linen shirt"
            required
            disabled={busy}
          />
        </label>
        <label class="field-label" for="garment-category">
          Category
          <select class="text-input" id="garment-category" bind:value={draft.category} disabled={busy}>
            {#each categories.slice(1) as category}
              <option value={category.value}>{category.label}</option>
            {/each}
          </select>
        </label>
        <label class="field-label" for="garment-colors">
          Colors
          <input
            class="text-input"
            id="garment-colors"
            bind:value={draft.colors}
            maxlength="200"
            placeholder="e.g. Oat, soft blue"
            disabled={busy}
          />
        </label>
        <label class="field-label" for="garment-pattern">
          Pattern
          <input
            class="text-input"
            id="garment-pattern"
            bind:value={draft.pattern}
            maxlength="120"
            placeholder="e.g. Fine stripe"
            disabled={busy}
          />
        </label>
        <label class="field-label" for="garment-sleeves">
          Sleeves / shape
          <input
            class="text-input"
            id="garment-sleeves"
            bind:value={draft.sleeves}
            maxlength="120"
            placeholder="e.g. Short sleeve"
            disabled={busy}
          />
        </label>
        <label class="field-label" for="garment-season">
          Season
          <input
            class="text-input"
            id="garment-season"
            bind:value={draft.season}
            maxlength="120"
            placeholder="e.g. Spring, all-season"
            disabled={busy}
          />
        </label>
        <label class="field-label" for="garment-style">
          Style
          <input
            class="text-input"
            id="garment-style"
            bind:value={draft.style}
            maxlength="120"
            placeholder="e.g. Relaxed, polished"
            disabled={busy}
          />
        </label>
        <label class="field-label field-wide" for="garment-notes">
          A note to yourself
          <textarea
            class="text-input notes-input"
            id="garment-notes"
            bind:value={draft.notes}
            maxlength="2000"
            placeholder="A favourite fit, how you like to wear it…"
            disabled={busy}
          ></textarea>
        </label>
      </div>

      <div class="dialog-actions">
        <button class="button button-quiet" type="button" disabled={busy} onclick={closeForm}>
          {busy ? 'Please wait…' : 'Cancel'}
        </button>
        <button
          class="button button-primary"
          type="submit"
          disabled={busy || imageDecisionRequired}
        >
          {#if saving}
            <span class="button-spinner"></span> Saving…
          {:else}
            Save piece <span aria-hidden="true">↗</span>
          {/if}
        </button>
      </div>
      <p class="save-note">
        The garment is not saved until you choose “Save piece”. Uploaded photos are stored locally and
        retained even if you cancel.
      </p>
    </form>
  </dialog>
{/if}
