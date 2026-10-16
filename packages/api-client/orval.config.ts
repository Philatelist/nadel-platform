import { defineConfig } from 'orval';

// The client is generated from the single contract api/openapi.yaml. Output goes to src/generated; never edit it by hand.
export default defineConfig({
  nadel: {
    input: { target: '../../api/openapi.yaml' },
    output: {
      mode: 'tags-split',
      target: './src/generated/endpoints.ts',
      schemas: './src/generated/model',
      client: 'react-query',
      httpClient: 'fetch',
      clean: true,
      baseUrl: '/api/v1',
      override: {
        mutator: { path: './src/http.ts', name: 'nadelFetch' },
        query: { useQuery: true, useSuspenseQuery: false },
        // responses are plain bodies (Me, PlotFeatureCollection); errors are thrown as ApiError
        fetch: { includeHttpResponseReturnType: false },
      },
    },
  },
});
