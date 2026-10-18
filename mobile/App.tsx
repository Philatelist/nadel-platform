import { messages, pickLocale } from '@nadel/shared';
import { palette, plotStatusColors, radius, spacing, type PlotStatusKey } from '@nadel/ui-tokens';
import { useFonts } from 'expo-font';
import { StatusBar } from 'expo-status-bar';
import { getLocales } from 'expo-localization';
import { Pressable, StyleSheet, Text, useColorScheme, View } from 'react-native';
import { fontFamily, fontMap } from './src/fonts';

const STATUSES: PlotStatusKey[] = ['RED', 'YELLOW', 'GREEN', 'GREY'];

export default function App() {
  const [fontsLoaded, fontError] = useFonts(fontMap);
  const scheme = useColorScheme() === 'dark' ? 'dark' : 'light';
  const colors = palette[scheme];
  const status = plotStatusColors[scheme];
  const t = messages(pickLocale(getLocales().map((l) => l.languageTag)));

  if (!fontsLoaded && !fontError) return null;

  return (
    <View style={[styles.screen, { backgroundColor: colors.background }]}>
      <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.line }]}>
        <Text style={[styles.title, { color: colors.ink }]}>{t.app.name}</Text>
        <Text style={[styles.hint, { color: colors.inkMuted }]}>{t.auth.signInHint}</Text>

        <View style={styles.legend}>
          {STATUSES.map((s) => (
            <View key={s} style={styles.legendItem}>
              <View style={[styles.swatch, { backgroundColor: status[s] }]} />
              <Text style={[styles.legendText, { color: colors.inkMuted }]}>
                {s === 'GREY' ? t.status.GREY_COMMON : t.status[s]}
              </Text>
            </View>
          ))}
        </View>

        {/* Keycloak sign-in (PKCE) arrives in phase 5 */}
        <Pressable
          accessibilityRole="button"
          style={({ pressed }) => [
            styles.button,
            { backgroundColor: colors.accent, opacity: pressed ? 0.85 : 1 },
          ]}
        >
          <Text style={[styles.buttonText, { color: colors.onAccent }]}>{t.auth.signIn}</Text>
        </Pressable>
      </View>
      <StatusBar style={scheme === 'dark' ? 'light' : 'dark'} />
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, justifyContent: 'center', padding: spacing[4] },
  card: { borderWidth: 1, borderRadius: radius.panel, padding: spacing[6], gap: spacing[4] },
  title: { fontSize: 32, fontFamily: fontFamily.serif.semibold },
  hint: { fontFamily: fontFamily.sans.regular, fontSize: 16, lineHeight: 22 },
  legend: { gap: spacing[2] },
  legendItem: { flexDirection: 'row', alignItems: 'center', gap: spacing[2] },
  legendText: { fontFamily: fontFamily.sans.regular },
  swatch: { width: 12, height: 12, borderRadius: 3 },
  button: { borderRadius: radius.control, minHeight: 44, justifyContent: 'center', alignItems: 'center' },
  buttonText: { fontSize: 16, fontFamily: fontFamily.sans.semibold },
});
