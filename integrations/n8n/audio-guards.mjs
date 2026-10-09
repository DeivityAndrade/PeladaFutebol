// This function is also embedded in the n8n Code node; keep it free of imports.
export function validateMedia(media, expectedMime) {
  const url = media.url;
  const mime = String(media.mime_type || '').split(';')[0].toLowerCase();
  if (typeof url !== 'string' || !/^https:\/\/(lookaside\.fbsbx\.com|graph\.facebook\.com)\/whatsapp_business\/attachments\/[?][^\s#]+$/.test(url))
    throw new Error('Destino de mídia inválido.');
  if (!Number.isInteger(media.file_size) || media.file_size <= 0 || media.file_size > 2 * 1024 * 1024)
    throw new Error('O áudio deve ter até 2 MB.');
  if (!['audio/ogg', 'audio/mp4', 'audio/m4a'].includes(mime) || mime !== String(expectedMime || '').split(';')[0].toLowerCase())
    throw new Error('Formato de áudio inválido.');
  return { url };
}
