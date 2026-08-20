# Admin Content Implementation Notes

Fecha: 2026-05-01

## Alcance

El bloque de contenido/admin queda conectado siguiendo la arquitectura actual:

Domain -> Repository -> UseCases -> ViewModel -> Compose UI.

No se introduce logica directa de Supabase en ViewModels.

## Datos fake

Las implementaciones fake de tienda, rafagas y noticias se mantienen solo como soporte de preview/desarrollo cuando `usePreviewData()` esta activo.

En modo real, `DataModule` enlaza:

- `CommerceRepository` con `SupabaseCommerceRepositoryImpl`.
- `NewsRepository` con `SupabaseNewsRepositoryImpl`.
- `ContentRepository` con `SupabaseContentRepositoryImpl`.

Por tanto, las pantallas de tienda/noticias no dependen de datos fake salvo en el modo de preview configurado.

## Pendiente funcional fuera de cliente

La compra transaccional con descuento de tickets, movimiento wallet y concesion de acceso/recurso debe cerrarse como RPC de Supabase antes de habilitar compras reales desde cliente. La capa admin ya modela y persiste items, ofertas, disponibilidad temporal y rafagas.
