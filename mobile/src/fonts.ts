import { IBMPlexMono_400Regular } from '@expo-google-fonts/ibm-plex-mono/400Regular';
import { IBMPlexMono_500Medium } from '@expo-google-fonts/ibm-plex-mono/500Medium';
import { IBMPlexSans_400Regular } from '@expo-google-fonts/ibm-plex-sans/400Regular';
import { IBMPlexSans_500Medium } from '@expo-google-fonts/ibm-plex-sans/500Medium';
import { IBMPlexSans_600SemiBold } from '@expo-google-fonts/ibm-plex-sans/600SemiBold';
import { IBMPlexSerif_600SemiBold } from '@expo-google-fonts/ibm-plex-serif/600SemiBold';
/**
 * Registered family names per role and weight. React Native selects a font by family name
 * only, so each weight is its own family and `fontWeight` must not be combined with these.
 */
export const fontFamily = {
  sans: {
    regular: 'IBMPlexSans_400Regular',
    medium: 'IBMPlexSans_500Medium',
    semibold: 'IBMPlexSans_600SemiBold',
  },
  serif: { semibold: 'IBMPlexSerif_600SemiBold' },
  mono: { regular: 'IBMPlexMono_400Regular', medium: 'IBMPlexMono_500Medium' },
} as const;

/** Font map for `useFonts`. */
export const fontMap = {
  [fontFamily.sans.regular]: IBMPlexSans_400Regular,
  [fontFamily.sans.medium]: IBMPlexSans_500Medium,
  [fontFamily.sans.semibold]: IBMPlexSans_600SemiBold,
  [fontFamily.serif.semibold]: IBMPlexSerif_600SemiBold,
  [fontFamily.mono.regular]: IBMPlexMono_400Regular,
  [fontFamily.mono.medium]: IBMPlexMono_500Medium,
};
