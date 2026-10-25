use crate::{cli::Theme, model::State};
use clap::ValueEnum;
use ratatui::style::{Color, Style};

#[derive(Clone, Copy, Debug)]
pub struct Palette {
    pub background: Color,
    pub foreground: Color,
    pub secondary: Color,
    pub border: Color,
    pub axes: Color,
    pub charge: Color,
    pub discharge: Color,
    pub neutral: Color,
    pub warning: Color,
    pub low_battery: Color,
}

impl Palette {
    pub fn style(self) -> Style {
        Style::default().fg(self.foreground).bg(self.background)
    }

    pub fn state(self, state: State) -> Color {
        match state {
            State::Charging => self.charge,
            State::Discharging => self.discharge,
            State::Mixed | State::Idle | State::Unknown => self.neutral,
        }
    }
}

impl std::fmt::Display for Theme {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(
            self.to_possible_value()
                .expect("all themes have CLI names")
                .get_name(),
        )
    }
}

impl Theme {
    /// Cycle through the CLI theme order without changing saved configuration.
    pub fn cycle(self, backwards: bool) -> Self {
        let themes = Self::value_variants();
        let index = themes
            .iter()
            .position(|theme| *theme == self)
            .expect("all themes are CLI variants");
        let offset = if backwards { themes.len() - 1 } else { 1 };
        themes[(index + offset) % themes.len()]
    }

    pub fn palette(self) -> Palette {
        match self {
            Self::Default | Self::HighContrast | Self::Mono => {
                let mono = self == Self::Mono;
                Palette {
                    background: Color::Reset,
                    foreground: Color::Reset,
                    secondary: if self == Self::Default {
                        Color::DarkGray
                    } else {
                        Color::Gray
                    },
                    border: Color::DarkGray,
                    axes: if mono { Color::White } else { Color::Cyan },
                    charge: if mono { Color::White } else { Color::Green },
                    discharge: if mono { Color::White } else { Color::Yellow },
                    neutral: Color::Gray,
                    warning: if mono { Color::White } else { Color::Yellow },
                    low_battery: if mono { Color::White } else { Color::Red },
                }
            }
            Self::TokyoNight | Self::TokyoNightStorm => rgb_palette(
                if self == Self::TokyoNight {
                    0x1a1b26
                } else {
                    0x24283b
                },
                [
                    0xc0caf5, 0xa9b1d6, 0x565f89, 0x7dcfff, 0x9ece6a, 0xe0af68, 0xbb9af7, 0xff9e64,
                ],
            ),
            Self::CatppuccinLatte => rgb_palette(
                0xeff1f5,
                [
                    0x4c4f69, 0x5c5f77, 0x8c8fa1, 0x007f8b, 0x38751d, 0x986900, 0x8839ef, 0xc3440b,
                ],
            ),
            Self::CatppuccinFrappe => rgb_palette(
                0x303446,
                [
                    0xc6d0f5, 0xb5bfe2, 0x737994, 0x81c8be, 0xa6d189, 0xe5c890, 0xca9ee6, 0xef9f76,
                ],
            ),
            Self::CatppuccinMacchiato => rgb_palette(
                0x24273a,
                [
                    0xcad3f5, 0xb8c0e0, 0x6e738d, 0x8bd5ca, 0xa6da95, 0xeed49f, 0xc6a0f6, 0xf5a97f,
                ],
            ),
            Self::CatppuccinMocha => rgb_palette(
                0x1e1e2e,
                [
                    0xcdd6f4, 0xbac2de, 0x6c7086, 0x94e2d5, 0xa6e3a1, 0xf9e2af, 0xcba6f7, 0xfab387,
                ],
            ),
            Self::Nord => rgb_palette(
                0x2e3440,
                [
                    0xeceff4, 0xd8dee9, 0x7b88a1, 0x88c0d0, 0xa3be8c, 0xebcb8b, 0xb48ead, 0xd08770,
                ],
            ),
        }
    }
}

fn rgb(value: u32) -> Color {
    Color::Rgb((value >> 16) as u8, (value >> 8) as u8, value as u8)
}

fn rgb_palette(background: u32, roles: [u32; 8]) -> Palette {
    let [
        foreground,
        secondary,
        border,
        axes,
        charge,
        discharge,
        neutral,
        warning,
    ] = roles.map(rgb);
    Palette {
        background: rgb(background),
        low_battery: if background == 0xeff1f5 {
            rgb(0xb91c35)
        } else {
            rgb(0xff8797)
        },
        foreground,
        secondary,
        border,
        axes,
        charge,
        discharge,
        neutral,
        warning,
    }
}
