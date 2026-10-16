import { messages, pickLocale } from '@nadel/shared';
import { palette, plotStatusColors, radius, spacing, type PlotStatusKey } from '@nadel/ui-tokens';
import { StatusBar } from 'expo-status-bar';
import { getLocales } from 'expo-localization';
import { Pressable, StyleSheet, Text, useColorScheme, View } from 'react-native';

const STATUSES: PlotStatusKey[] = ['RED', 'YELLOW', 'GREEN', 'GREY'];

export default function App() {
  const scheme = useColorScheme() === 'dark' ? 'dark' : 'light';
  const colors = palette[scheme];
  const status = plotStatusColors[scheme];
  const t = messages(pickLocale(getLocales().map((l) => l.languageTag)));

  return (
    <View style={[styles.screen, { backgroundColor: colors.background }]}>
      <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.line }]}>
        <Text style={[styles.title, { color: colors.ink }]}>{t.app.name}</Text>
        <Text style={[styles.hint, { color: colors.muted }]}>{t.auth.signInHint}</Text>

        <View style={styles.legend}>
          {STATUSES.map((s) => (
            <View key={s} style={styles.legendItem}>
              <View style={[styles.swatch, { backgroundColor: status[s] }]} />
              <Text style={{ color: colors.muted }}>{t.status[s]}</Text>
            </View>
          ))}
        </View>

        {/* Keycloak sign-in (PKCE) arrives in phase 5 */}
        <Pressable
          accessibilityRole="button"
          style={({ pressed }) => [styles.button, { backgroundColor: colors.accent, opacity: pressed ? 0.85 : 1 }]}
        >
          <Text style={[styles.buttonText, { color: colors.onAccent }]}>{t.auth.signIn}</Text>
        </Pressable>
      </View>
      <StatusBar style={scheme === 'dark' ? 'light' : 'dark'} />
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, justifyContent: 'center', padding: spacing.lg },
  card: { borderWidth: 1, borderRadius: radius.lg, padding: spacing.xl, gap: spacing.lg },
  title: { fontSize: 32, fontWeight: '700' },
  hint: { fontSize: 16, lineHeight: 22 },
  legend: { gap: spacing.sm },
  legendItem: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  swatch: { width: 12, height: 12, borderRadius: 3 },
  button: { borderRadius: radius.md, paddingVertical: spacing.md, alignItems: 'center' },
  buttonText: { fontSize: 16, fontWeight: '600' },
});
