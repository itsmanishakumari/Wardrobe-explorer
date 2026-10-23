import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/svelte'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App.svelte'

type Garment = {
  id: number; name: string; category: 'TOP' | 'BOTTOM' | 'ONE_PIECE' | 'SHOES' | 'LAYER' | 'ACCESSORY'
  colors: string; pattern: string; sleeves: string; season: string; style: string; notes: string
  imageId: string | null; imageUrl: string | null; createdAt: string
}

const garment = (id: number, name: string, category: Garment['category'] = 'TOP', extras: Partial<Garment> = {}): Garment => ({
  id, name, category, colors: 'Ivory', pattern: '', sleeves: 'Long sleeve', season: 'All season', style: 'Relaxed', notes: '',
  imageId: null, imageUrl: null, createdAt: '2026-01-01T00:00:00Z', ...extras,
})
const offlineStatus = { ollamaAvailable: false, visionModel: 'llava', textModel: 'llama3', visionModelReady: false, textModelReady: false, message: 'Ollama is unavailable' }
const jsonResponse = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
function deferredResponse() {
  let resolve!: (response: Response) => void
  const promise = new Promise<Response>((done) => { resolve = done })
  return { promise, resolve }
}

describe('The Daily Edit', () => {
  beforeEach(() => {
    let blobId = 0
    vi.spyOn(URL, 'createObjectURL').mockImplementation(() => `blob:photo-${++blobId}`)
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/garments') return jsonResponse([])
      if (url === '/api/status') return jsonResponse(offlineStatus)
      return jsonResponse({ message: 'Unexpected request' }, 404)
    }))
  })
  afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

  it('renders a genuine empty wardrobe and remains usable when Ollama is offline', async () => {
    render(App)
    expect(await screen.findByRole('heading', { name: 'Your wardrobe, your way.' })).toBeInTheDocument()
    expect(screen.getByText('Assistant not connected')).toBeInTheDocument()
    expect(screen.getByText(offlineStatus.message)).toBeInTheDocument()
    expect(screen.getByText('0', { selector: 'strong' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /add your first piece/i })).toBeEnabled()
  })

  it('creates a manually entered piece through the API and updates the wardrobe', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/garments' && init?.method === 'POST') return jsonResponse(garment(7, 'Linen overshirt', 'TOP', { colors: 'Sage' }), 201)
      if (url === '/api/garments') return jsonResponse([])
      if (url === '/api/status') return jsonResponse(offlineStatus)
      return jsonResponse({ message: 'Unexpected request' }, 404)
    })
    render(App)
    await screen.findByRole('heading', { name: 'Your wardrobe, your way.' })
    await fireEvent.click(screen.getByRole('button', { name: /add your first piece/i }))
    const dialog = screen.getByRole('dialog')
    await fireEvent.input(within(dialog).getByLabelText(/piece name/i), { target: { value: 'Linen overshirt' } })
    await fireEvent.input(within(dialog).getByLabelText(/colors/i), { target: { value: 'Sage' } })
    await fireEvent.click(within(dialog).getByRole('button', { name: /save piece/i }))
    expect(await screen.findByRole('heading', { name: 'Linen overshirt' })).toBeInTheDocument()
    const post = fetchMock.mock.calls.find(([url, init]) => String(url) === '/api/garments' && init?.method === 'POST')
    expect(post).toBeDefined()
    expect(JSON.parse(String(post?.[1]?.body))).toMatchObject({ name: 'Linen overshirt', colors: 'Sage', category: 'TOP', imageId: null })
  })

  it('shows photo analysis as an editable draft and saves only after explicit confirmation', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/garments' && init?.method === 'POST') return jsonResponse(garment(9, 'Soft cotton shirt', 'TOP', { imageId: 'local-photo.jpg', imageUrl: '/api/images/local-photo.jpg', colors: 'Blue' }), 201)
      if (url === '/api/garments') return jsonResponse([])
      if (url === '/api/status') return jsonResponse(offlineStatus)
      if (url === '/api/images') return jsonResponse({ imageId: 'local-photo.jpg', imageUrl: '/api/images/local-photo.jpg' })
      if (url === '/api/analysis') return jsonResponse({ draft: { name: 'Soft cotton shirt', category: 'TOP', colors: 'Blue', pattern: 'Solid', sleeves: 'Long sleeve', season: 'Spring', style: 'Casual', notes: '' }, message: 'Please review these details.' })
      return jsonResponse({ message: 'Unexpected request' }, 404)
    })
    render(App)
    await screen.findByRole('heading', { name: 'Your wardrobe, your way.' })
    await fireEvent.click(screen.getByRole('button', { name: /add your first piece/i }))
    const dialog = screen.getByRole('dialog')
    const file = new File(['local test image'], 'shirt.jpg', { type: 'image/jpeg' })
    await fireEvent.change(within(dialog).getByLabelText(/choose a photo/i), { target: { files: [file] } })
    await waitFor(() => expect(within(dialog).getByRole('button', { name: /suggest details/i })).toBeEnabled())
    await fireEvent.click(within(dialog).getByRole('button', { name: /suggest details/i }))
    expect(await within(dialog).findByText('Please review these details.')).toBeInTheDocument()
    expect(within(dialog).getByLabelText(/piece name/i)).toHaveValue('Soft cotton shirt')
    expect(fetchMock).not.toHaveBeenCalledWith('/api/garments', expect.objectContaining({ method: 'POST' }))
    await fireEvent.click(within(dialog).getByRole('button', { name: /save piece/i }))
    expect(await screen.findByRole('heading', { name: 'Soft cotton shirt' })).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith('/api/garments', expect.objectContaining({ method: 'POST' }))
  })

  it('renders outfit results with only matching saved garments and reports generation errors', async () => {
    let failOutfits = false
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/garments') return jsonResponse([garment(1, 'Ivory blouse', 'TOP', { imageUrl: '/api/images/blouse.jpg' }), garment(2, 'Wide-leg trousers', 'BOTTOM', { imageUrl: '/api/images/trousers.jpg' })])
      if (url === '/api/status') return jsonResponse(offlineStatus)
      if (url === '/api/outfits' && failOutfits) return jsonResponse({ message: 'Install a text model to get outfit ideas.' }, 503)
      if (url === '/api/outfits') return jsonResponse({ outfits: [{ title: 'Soft structure', explanation: 'An easy polished pairing.', garmentIds: [1, 2, 456] }], message: '' })
      return jsonResponse({ message: 'Unexpected request' }, 404)
    })
    render(App)
    expect(await screen.findByRole('heading', { name: 'Ivory blouse' })).toBeInTheDocument()
    await fireEvent.click(screen.getByRole('button', { name: /find an outfit/i }))
    expect(await screen.findByRole('heading', { name: 'Soft structure' })).toBeInTheDocument()
    const outfit = screen.getByRole('heading', { name: 'Soft structure' }).closest('article')!
    expect(within(outfit).getByText('Ivory blouse')).toBeInTheDocument()
    expect(within(outfit).getByText('Wide-leg trousers')).toBeInTheDocument()
    expect(within(outfit).getByRole('group', { name: 'Pieces in Soft structure' })).toBeInTheDocument()
    expect(within(outfit).getAllByRole('img').map((image) => image.getAttribute('alt'))).toEqual(['Ivory blouse', 'Wide-leg trousers'])
    expect(within(outfit).queryByText('456')).not.toBeInTheDocument()
    failOutfits = true
    await fireEvent.click(screen.getByRole('button', { name: /find an outfit/i }))
    expect(await screen.findByText(/Install a text model to get outfit ideas/i)).toBeInTheDocument()
  })

  it('filters the wardrobe by category and text search', async () => {
    vi.mocked(fetch).mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/garments') return jsonResponse([garment(1, 'Blue linen shirt', 'TOP'), garment(2, 'Black trousers', 'BOTTOM')])
      if (url === '/api/status') return jsonResponse(offlineStatus)
      return jsonResponse({}, 404)
    })
    render(App)
    expect(await screen.findByRole('heading', { name: 'Blue linen shirt' })).toBeInTheDocument()
    await fireEvent.click(screen.getByRole('button', { name: 'Bottoms' }))
    expect(screen.getByRole('heading', { name: 'Black trousers' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Blue linen shirt' })).not.toBeInTheDocument()
    await fireEvent.click(screen.getByRole('button', { name: 'Everything' }))
    await fireEvent.input(screen.getByRole('searchbox', { name: 'Search wardrobe' }), { target: { value: 'linen' } })
    expect(screen.getByRole('heading', { name: 'Blue linen shirt' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Black trousers' })).not.toBeInTheDocument()
  })

  it('resets form state after native Escape cancellation and allows reopening a fresh draft', async () => {
    render(App)
    await screen.findByRole('heading', { name: 'Your wardrobe, your way.' })
    await fireEvent.click(screen.getByRole('button', { name: /add your first piece/i }))
    const dialog = screen.getByRole('dialog') as HTMLDialogElement
    await fireEvent.input(within(dialog).getByLabelText(/piece name/i), { target: { value: 'Unsaved draft' } })
    const cancel = new Event('cancel', { cancelable: true })
    // jsdom has no native Escape default action: emulate the browser's close after cancel.
    expect(dialog.dispatchEvent(cancel)).toBe(true)
    dialog.close()
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(dialog).not.toBeInTheDocument()
    await fireEvent.click(screen.getByRole('button', { name: /add your first piece/i }))
    expect(screen.getByRole('dialog')).toHaveAttribute('open')
    expect(screen.getByLabelText(/piece name/i)).toHaveValue('')
  })

  it('prevents Escape while a photo upload is in progress and revokes its temporary URL on success', async () => {
    const upload = deferredResponse()
    vi.mocked(fetch).mockImplementation(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/garments') return jsonResponse([])
      if (String(input) === '/api/status') return jsonResponse(offlineStatus)
      if (String(input) === '/api/images') return upload.promise
      return jsonResponse({}, 404)
    })
    render(App)
    await screen.findByRole('heading', { name: 'Your wardrobe, your way.' })
    await fireEvent.click(screen.getByRole('button', { name: /add your first piece/i }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText(/garment is not saved.*Uploaded photos.*retained even if you cancel/i)).toBeInTheDocument()
    await fireEvent.change(within(dialog).getByLabelText(/choose a photo/i), { target: { files: [new File(['image'], 'shirt.jpg', { type: 'image/jpeg' })] } })
    const cancel = new Event('cancel', { cancelable: true })
    expect(dialog.dispatchEvent(cancel)).toBe(false)
    expect(cancel.defaultPrevented).toBe(true)
    expect(within(dialog).getByRole('button', { name: /close dialog/i })).toBeDisabled()
    expect(URL.revokeObjectURL).not.toHaveBeenCalled()
    upload.resolve(jsonResponse({ imageId: 'photo.jpg', imageUrl: '/api/images/photo.jpg' }))
    await waitFor(() => expect(within(dialog).getByRole('button', { name: /suggest details/i })).toBeEnabled())
    expect(URL.revokeObjectURL).toHaveBeenCalledExactlyOnceWith('blob:photo-1')
    expect(within(dialog).getByRole('img', { name: /selected garment preview/i })).toHaveAttribute('src', '/api/images/photo.jpg')
    await fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    expect(URL.revokeObjectURL).toHaveBeenCalledTimes(1)
    expect(fetch).not.toHaveBeenCalledWith('/api/garments', expect.objectContaining({ method: 'POST' }))
  })

  it.each(['remove', 'replace', 'cancel', 'native close', 'unmount'] as const)('releases a failed-upload blob preview on %s', async (action) => {
    vi.mocked(fetch).mockImplementation(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/garments') return jsonResponse([])
      if (String(input) === '/api/status') return jsonResponse(offlineStatus)
      return jsonResponse({ message: 'Upload failed. Please retry.' }, 503)
    })
    const view = render(App)
    await screen.findByRole('heading', { name: 'Your wardrobe, your way.' })
    await fireEvent.click(screen.getByRole('button', { name: /add your first piece/i }))
    const dialog = screen.getByRole('dialog') as HTMLDialogElement
    const file = new File(['image'], 'shirt.jpg', { type: 'image/jpeg' })
    await fireEvent.change(within(dialog).getByLabelText(/choose a photo/i), { target: { files: [file] } })
    await within(dialog).findByText('Upload failed. Please retry.')
    expect(within(dialog).getByRole('button', { name: /save piece/i })).toBeDisabled()
    expect(URL.revokeObjectURL).not.toHaveBeenCalled()
    if (action === 'remove') await fireEvent.click(within(dialog).getByRole('button', { name: 'Remove photo' }))
    if (action === 'replace') await fireEvent.change(within(dialog).getByLabelText(/choose another/i), { target: { files: [file] } })
    if (action === 'cancel') await fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    if (action === 'native close') dialog.close()
    if (action === 'unmount') await view.unmount()
    await waitFor(() => expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:photo-1'))
    expect(vi.mocked(URL.revokeObjectURL).mock.calls.filter(([url]) => url === 'blob:photo-1')).toHaveLength(1)
    if (action === 'remove') expect(within(dialog).getByRole('button', { name: /save piece/i })).toBeEnabled()
  })

  it.each(['create', 'edit', 'delete'] as const)('discards an in-flight recommendation after a successful wardrobe %s', async (change) => {
    const generation = deferredResponse()
    let requests = 0
    vi.mocked(fetch).mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/status') return jsonResponse(offlineStatus)
      if (url === '/api/garments' && init?.method === 'POST') return jsonResponse(garment(3, 'New shirt'), 201)
      if (url === '/api/garments/1' && init?.method === 'PUT') return jsonResponse(garment(1, 'Updated dress', 'ONE_PIECE'))
      if (url === '/api/garments/1' && init?.method === 'DELETE') return new Response(null, { status: 204 })
      if (url === '/api/garments') return jsonResponse([garment(1, 'Linen dress', 'ONE_PIECE'), garment(2, 'Cotton dress', 'ONE_PIECE')])
      if (url === '/api/outfits') {
        requests += 1
        return requests === 1 ? generation.promise : jsonResponse({ outfits: [{ title: 'Fresh idea', explanation: 'Updated inventory.', garmentIds: [2] }], message: '' })
      }
      return jsonResponse({}, 404)
    })
    render(App)
    await screen.findByRole('heading', { name: 'Linen dress' })
    await fireEvent.click(screen.getByRole('button', { name: /find an outfit/i }))
    if (change === 'delete') {
      const card = screen.getByRole('heading', { name: 'Linen dress' }).closest('article')!
      await fireEvent.click(within(card).getByRole('button', { name: 'Remove' }))
      await fireEvent.click(within(card).getByRole('button', { name: 'Remove' }))
      await waitFor(() => expect(screen.queryByRole('heading', { name: 'Linen dress' })).not.toBeInTheDocument())
    } else {
      await fireEvent.click(screen.getByRole('button', { name: change === 'edit' ? 'Edit Linen dress' : /add a piece/i }))
      const dialog = screen.getByRole('dialog')
      await fireEvent.input(within(dialog).getByLabelText(/piece name/i), { target: { value: change === 'edit' ? 'Updated dress' : 'New shirt' } })
      await fireEvent.click(within(dialog).getByRole('button', { name: /save piece/i }))
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    }
    generation.resolve(jsonResponse({ outfits: [{ title: 'Stale idea', explanation: 'Old inventory.', garmentIds: [1] }], message: 'Old response' }))
    await waitFor(() => expect(screen.getByRole('button', { name: /find an outfit/i })).toBeEnabled())
    expect(screen.queryByRole('heading', { name: 'Stale idea' })).not.toBeInTheDocument()
    expect(screen.getByText(/your wardrobe changed/i)).toBeInTheDocument()
    expect(screen.queryByText('Old response')).not.toBeInTheDocument()
    await fireEvent.click(screen.getByRole('button', { name: /find an outfit/i }))
    expect(await screen.findByRole('heading', { name: 'Fresh idea' })).toBeInTheDocument()
    expect(screen.queryByText(/your wardrobe changed/i)).not.toBeInTheDocument()
  })

  it('ignores a stale generation error after the wardrobe changes', async () => {
    const generation = deferredResponse()
    vi.mocked(fetch).mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/api/status') return jsonResponse(offlineStatus)
      if (String(input) === '/api/outfits') return generation.promise
      if (init?.method === 'DELETE') return new Response(null, { status: 204 })
      return jsonResponse([garment(1, 'Linen dress', 'ONE_PIECE')])
    })
    render(App)
    await screen.findByRole('heading', { name: 'Linen dress' })
    await fireEvent.click(screen.getByRole('button', { name: /find an outfit/i }))
    await fireEvent.click(screen.getByRole('button', { name: 'Remove' }))
    await fireEvent.click(screen.getByRole('button', { name: 'Remove' }))
    await screen.findByRole('heading', { name: 'Your wardrobe, your way.' })
    generation.resolve(jsonResponse({ message: 'Outdated generation failure' }, 503))
    await waitFor(() => expect(screen.getByRole('button', { name: /find an outfit/i })).toBeEnabled())
    expect(screen.queryByText(/Outdated generation failure/i)).not.toBeInTheDocument()
    expect(screen.getByText(/your wardrobe changed/i)).toBeInTheDocument()
  })
})