// Behind Cloudflare, CF-Connecting-IP is set by the edge and cannot be forged by the client
// (the edge overwrites any client-supplied value). Fall back to 'unknown' when absent (local dev).
export function clientIp(request) {
  const ip = request.headers.get('cf-connecting-ip');
  return ip ? ip.trim().replace(/^::ffff:/i, '') : 'unknown';
}
